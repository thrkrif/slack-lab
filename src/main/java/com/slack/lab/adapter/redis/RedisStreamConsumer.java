package com.slack.lab.adapter.redis;

import com.slack.lab.core.service.EventProcessor;
import com.slack.lab.config.WorkerProperties;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.QueueProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
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
import org.springframework.stereotype.Component;

/**
 * Redis Streams 소비 어댑터(M19). 소비 그룹에서 메시지를 읽어 {@link EventProcessor}에 넘기고, 죽은 소비자의 pending
 * 항목을 주기적으로 회수한다. 처리·상태 기록·확정은 코어({@link EventProcessor})의 몫이다. {@code worker.concurrency}개
 * 스레드가 각자 이름 있는 소비자로 블로킹 read를 반복한다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class RedisStreamConsumer {

    private static final Logger log = LoggerFactory.getLogger(RedisStreamConsumer.class);
    private static final long RECLAIM_INTERVAL_MS = 30_000;
    private static final String RECLAIM_CONSUMER = "reclaimer";

    private final StringRedisTemplate redis;
    private final QueueProperties queueProps;
    private final WorkerProperties workerProps;
    private final EventProcessor processor;

    private ExecutorService pool;
    // 죽은 소비자 항목 회수 전용 스케줄러다. 시도별 임대 갱신은 코어 EventProcessor가 자기 스케줄러로 돌린다.
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public RedisStreamConsumer(StringRedisTemplate redis, QueueProperties queueProps, WorkerProperties workerProps,
            EventProcessor processor) {
        this.redis = redis;
        this.queueProps = queueProps;
        this.workerProps = workerProps;
        this.processor = processor;
    }

    @PostConstruct
    void start() {
        ensureGroupExists();
        running = true;
        pool = Executors.newFixedThreadPool(workerProps.concurrency());
        for (int i = 0; i < workerProps.concurrency(); i++) {
            String consumer = "worker-" + i + "-" + UUID.randomUUID().toString().substring(0, 8);
            pool.submit(() -> loop(consumer));
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        scheduler.scheduleWithFixedDelay(this::reclaimStale, RECLAIM_INTERVAL_MS, RECLAIM_INTERVAL_MS,
                TimeUnit.MILLISECONDS);
        log.info("워커 시작 concurrency={} stream={} group={}", workerProps.concurrency(), queueProps.streamKey(),
                queueProps.group());
    }

    @PreDestroy
    void stop() {
        running = false;
        if (pool != null) {
            pool.shutdownNow();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "event-worker-scheduler");
            t.setDaemon(true);
            return t;
        };
    }

    private void ensureGroupExists() {
        try {
            redis.opsForStream().createGroup(queueProps.streamKey(), ReadOffset.from("0"), queueProps.group());
        } catch (RuntimeException e) {
            // Spring이 Lettuce 예외를 RedisSystemException("Error in execution")으로 감싸므로, 원본 BUSYGROUP
            // 메시지는 getMessage()가 아니라 cause 체인에 있다(테스트가 그룹을 반복 생성할 때 실측).
            if (!isBusyGroup(e)) {
                throw e;
            }
        }
    }

    private static boolean isBusyGroup(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("BUSYGROUP")) {
                return true;
            }
        }
        return false;
    }

    private void loop(String consumer) {
        // 여러 워커가 같은 그룹을 소비할 때 XPENDING의 소비자 이름과 컨테이너를 연결하는 유일한 단서다(M17 kill 실험).
        log.info("워커 소비자 시작 consumer={}", consumer);
        while (running) {
            try {
                // block 값은 spring.data.redis.timeout(1s, MAJOR-2)보다 짧아야 한다 — 아니면 서버의 BLOCK이
                // 끝나기 전에 Lettuce의 명령 타임아웃이 먼저 터져 매번 QueryTimeoutException으로 끝난다.
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(queueProps.group(), consumer),
                        StreamReadOptions.empty().count(1).block(Duration.ofMillis(900)),
                        StreamOffset.create(queueProps.streamKey(), ReadOffset.lastConsumed()));
                if (records != null) {
                    for (MapRecord<String, Object, Object> record : records) {
                        process(record);
                    }
                }
            } catch (QueryTimeoutException e) {
                // BLOCK 타임아웃 — 정상. 다음 주기로 넘어간다(이 예외는 실제로는 거의 나지 않고, 보통 빈 결과로 온다).
            } catch (RuntimeException e) {
                if (!running) {
                    return; // shutdownNow()로 인한 인터럽트/연결 종료
                }
                log.error("워커 루프 오류 consumer={}", consumer, e);
                sleepQuietly(1000);
            }
        }
    }

    private void reclaimStale() {
        try {
            long total = redis.opsForStream().pending(queueProps.streamKey(), queueProps.group())
                    .getTotalPendingMessages();
            if (total == 0) {
                return;
            }
            PendingMessages pending = redis.opsForStream().pending(queueProps.streamKey(), queueProps.group(),
                    Range.unbounded(), 200);
            List<RecordId> stale = new ArrayList<>();
            for (PendingMessage m : pending) {
                if (m.getElapsedTimeSinceLastDelivery().toMillis() >= queueProps.claimMinIdleMs()) {
                    stale.add(m.getId());
                }
            }
            if (stale.isEmpty()) {
                return;
            }
            List<MapRecord<String, Object, Object>> reclaimed = redis.opsForStream().claim(queueProps.streamKey(),
                    queueProps.group(), RECLAIM_CONSUMER, XClaimOptions.minIdleMs(queueProps.claimMinIdleMs()).ids(stale));
            log.info("죽은 소비자 항목 회수 stale_count={} reclaimed_count={}", stale.size(), reclaimed.size());
            // MINOR-4(알려진 문제, 미수정): 여기서 스케줄러 스레드가 직접 process()를 호출해 handler.handle()까지
            // 수행한다. worker.concurrency로 고정한 동시성 상한(M9, LLM 동시성 1)이 회수 경로에서는 깨진다
            // (풀 스레드 concurrency개 + 이 스케줄러 스레드까지 최대 concurrency+1). 회수는 드물게 발생하므로
            // 지금은 남겨두고, 고치려면 회수된 레코드를 pool에 제출해 동시성 상한 안에서 처리해야 한다.
            for (MapRecord<String, Object, Object> record : reclaimed) {
                process(record);
            }
        } catch (RuntimeException e) {
            log.error("회수 주기 오류", e);
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        String streamId = record.getId().getValue();
        RedisDelivery delivery = RedisDelivery.of(redis, queueProps.streamKey(), queueProps.group(), streamId,
                record.getValue());
        if (delivery == null) {
            log.error("event_id 없는 큐 메시지 — 건너뜀 stream_id={}", streamId);
            return;
        }
        processor.process(delivery);
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
