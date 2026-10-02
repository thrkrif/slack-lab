package com.slack.lab.adapter.redis;

import com.slack.lab.core.port.ChatNotifier;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.ReactionProperties;
import com.slack.lab.core.model.ReactionResult;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStreamCommands.XClaimOptions;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 즉시 반응(M15). 처리 스트림과 분리된 반응 스트림을 소비해 원 메시지에 이모지를 붙인다. 답변 처리와 무관하게
 * 도는 독립 스레드라 LLM이 밀려 있어도 반응은 빠르다. 보조 기능이므로 실패해도 재시도하지 않고 로그만 남긴 뒤
 * 항목을 지운다. 소비자가 죽으면 짧은 min-idle의 회수가 다른 소비자에게 넘긴다.
 */
@Component
@ConditionalOnProperty(name = "queue.backend", havingValue = "redis", matchIfMissing = true)
@ConditionalOnRole({AppRole.REACTOR, AppRole.WORKER, AppRole.ALL})
public class ReactionConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReactionConsumer.class);
    private static final long RECLAIM_INTERVAL_MS = 5_000;

    private final StringRedisTemplate redis;
    private final ReactionProperties props;
    private final ChatNotifier slack;

    private Thread loopThread;
    private ScheduledExecutorService reclaimer;
    private volatile boolean running;

    public ReactionConsumer(StringRedisTemplate redis, ReactionProperties props, ChatNotifier slack) {
        this.redis = redis;
        this.props = props;
        this.slack = slack;
    }

    @PostConstruct
    void start() {
        ensureGroup();
        running = true;
        String consumer = "reactor-" + UUID.randomUUID().toString().substring(0, 8);
        loopThread = new Thread(() -> loop(consumer), "reaction-consumer");
        loopThread.setDaemon(true);
        loopThread.start();
        reclaimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "reaction-reclaimer");
            t.setDaemon(true);
            return t;
        });
        reclaimer.scheduleWithFixedDelay(this::reclaimStale, RECLAIM_INTERVAL_MS, RECLAIM_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        log.info("반응 소비자 시작 stream={} group={} emoji={}", props.streamKey(), props.group(), props.emoji());
    }

    @PreDestroy
    void stop() {
        running = false;
        if (loopThread != null) {
            loopThread.interrupt();
        }
        if (reclaimer != null) {
            reclaimer.shutdownNow();
        }
    }

    private void ensureGroup() {
        try {
            redis.opsForStream().createGroup(props.streamKey(), ReadOffset.from("0"), props.group());
        } catch (RuntimeException e) {
            for (Throwable t = e; t != null; t = t.getCause()) {
                if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                    return;
                }
            }
            throw e;
        }
    }

    private void loop(String consumer) {
        // 명령 타임아웃이 나면 서버는 이미 항목을 이 소비자에게 전달했을 수 있다(응답만 놓침). '>'로는 자기 PEL을
        // 다시 읽지 못하므로, 다음 반복에서 '0'으로 한 번 읽어 방치를 막는다(실측: 회수까지 13.5초 지연).
        boolean readOwnPending = false;
        while (running) {
            try {
                // block은 spring.data.redis.timeout(1s)보다 짧아야 한다(EventWorker와 같은 이유).
                // count 1: 한 묶음을 오래 붙들면 뒤쪽 항목이 회수 min-idle을 넘겨 이중 처리된다.
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(props.group(), consumer),
                        readOwnPending ? StreamReadOptions.empty().count(1)
                                : StreamReadOptions.empty().count(1).block(Duration.ofMillis(900)),
                        StreamOffset.create(props.streamKey(),
                                readOwnPending ? ReadOffset.from("0") : ReadOffset.lastConsumed()));
                readOwnPending = false;
                if (records != null) {
                    for (MapRecord<String, Object, Object> r : records) {
                        handle(r);
                    }
                }
            } catch (QueryTimeoutException e) {
                readOwnPending = true;
            } catch (Throwable e) {
                // Error까지 잡는다: 데몬 스레드가 로그 없이 죽으면 반응이 조용히 멈춘다(함정 3).
                if (!running) {
                    return;
                }
                log.error("반응 루프 오류 consumer={}", consumer, e);
                sleepQuietly(1000);
            }
        }
    }

    void handle(MapRecord<String, Object, Object> record) {
        String eventId = str(record, "event_id");
        String channel = str(record, "channel");
        String ts = str(record, "ts");
        long receivedAt = parseLong(str(record, "received_at"));
        if (props.experimentDelayMs() > 0) {
            sleepQuietly(props.experimentDelayMs());
        }
        ReactionResult result = slack.addReaction(channel, ts, props.emoji());
        if ("interrupted".equals(result.detail())) {
            // 종료 중 인터럽트로 결과가 흐려졌을 수 있다 — ACK하지 않고 PEL에 남겨 다른 소비자가 회수하게 한다.
            return;
        }
        if (result.ok()) {
            // 최초 수신 → 반응 성공. 수신 서버 시계와 같은 호스트/동기화된 시계를 전제로 한다(PRD §5 측정 경계).
            log.info("반응 성공 event_id={} reaction_ms={} detail={}", eventId,
                    receivedAt > 0 ? System.currentTimeMillis() - receivedAt : -1, result.detail());
        } else {
            // 보조 기능이라 재시도하지 않는다. 답글 처리에는 영향이 없다.
            log.warn("반응 실패(재시도 안 함) event_id={} reason={}", eventId, result.detail());
        }
        ack(record.getId());
    }

    private void ack(RecordId id) {
        try {
            RedisStreams.ackDel(redis, props.streamKey(), props.group(), id.getValue());
        } catch (RuntimeException e) {
            // ACK 실패는 다음 회수 때 같은 반응을 한 번 더 붙이려 시도한다 — already_reacted라 무해하다.
            log.warn("반응 ACK 실패 stream_id={} reason={}", id.getValue(), e.getClass().getSimpleName());
        }
    }

    /** 죽은 소비자의 항목을 짧은 min-idle로 다른 소비자에게 넘긴다. */
    void reclaimStale() {
        try {
            if (redis.opsForStream().pending(props.streamKey(), props.group()).getTotalPendingMessages() == 0) {
                return;
            }
            PendingMessages pending = redis.opsForStream().pending(props.streamKey(), props.group(),
                    Range.unbounded(), 200);
            List<RecordId> stale = new ArrayList<>();
            for (PendingMessage m : pending) {
                if (m.getElapsedTimeSinceLastDelivery().toMillis() >= props.reclaimMinIdleMs()) {
                    stale.add(m.getId());
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> claimed = redis.opsForStream().claim(props.streamKey(),
                    props.group(), "reclaimer", XClaimOptions.minIdleMs(props.reclaimMinIdleMs()).ids(stale));
            log.info("반응 죽은 소비자 항목 회수 count={}", claimed.size());
            for (MapRecord<String, Object, Object> r : claimed) {
                handle(r);
            }
        } catch (RuntimeException e) {
            log.error("반응 회수 오류", e);
        }
    }

    private static String str(MapRecord<String, Object, Object> r, String key) {
        Object v = r.getValue().get(key);
        return v == null ? "" : v.toString();
    }

    private static long parseLong(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
