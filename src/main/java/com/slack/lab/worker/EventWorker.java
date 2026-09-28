package com.slack.lab.worker;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.event.HandlingResult;
import com.slack.lab.event.SlackEventHandler;
import com.slack.lab.event.SlackMessageEvent;
import com.slack.lab.queue.QueueProperties;
import com.slack.lab.state.ClaimOutcome;
import com.slack.lab.state.ClaimRequest;
import com.slack.lab.state.Finalization;
import com.slack.lab.state.ProcessingStateStore;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
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
import org.springframework.stereotype.Component;

/**
 * 큐를 소비해 M11 선점 결과표대로 처리 권한을 선점하고, 핸들러를 호출한 뒤 결과를 종료 상태로 확정한다
 * (PLAN 2단계 M12). {@code worker.concurrency}개 스레드가 각자 이름 있는 소비자로 블로킹 read를 반복하고,
 * 별도 스케줄러가 주기적으로 죽은 소비자의 pending 항목을 회수한다(XAUTOCLAIM 대신 pending+claim 조합 —
 * Spring Data Redis 3.4.1에 XAUTOCLAIM 전용 API가 없다).
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class EventWorker {

    private static final Logger log = LoggerFactory.getLogger(EventWorker.class);
    private static final long RECLAIM_INTERVAL_MS = 30_000;
    private static final String RECLAIM_CONSUMER = "reclaimer";

    private final StringRedisTemplate redis;
    private final QueueProperties queueProps;
    private final WorkerProperties workerProps;
    private final ProcessingStateStore store;
    private final SlackEventHandler handler;

    private ExecutorService pool;
    private ScheduledExecutorService reclaimScheduler;
    private volatile boolean running;

    public EventWorker(StringRedisTemplate redis, QueueProperties queueProps, WorkerProperties workerProps,
            ProcessingStateStore store, SlackEventHandler handler) {
        this.redis = redis;
        this.queueProps = queueProps;
        this.workerProps = workerProps;
        this.store = store;
        this.handler = handler;
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
        reclaimScheduler = Executors.newSingleThreadScheduledExecutor();
        reclaimScheduler.scheduleWithFixedDelay(this::reclaimStale, RECLAIM_INTERVAL_MS, RECLAIM_INTERVAL_MS,
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
        if (reclaimScheduler != null) {
            reclaimScheduler.shutdownNow();
        }
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
        while (running) {
            try {
                List<MapRecord<String, Object, Object>> records = redis.opsForStream().read(
                        Consumer.from(queueProps.group(), consumer),
                        StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
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
            for (MapRecord<String, Object, Object> record : reclaimed) {
                process(record);
            }
        } catch (RuntimeException e) {
            log.error("회수 주기 오류", e);
        }
    }

    private void process(MapRecord<String, Object, Object> record) {
        Map<Object, Object> fields = record.getValue();
        String streamId = record.getId().getValue();
        String eventId = str(fields, "event_id");
        if (eventId == null) {
            log.error("event_id 없는 큐 메시지 — 건너뜀 stream_id={}", streamId);
            return;
        }
        long gen = parseLongOr(str(fields, "gen"), 0);
        long receivedAt = parseLongOr(str(fields, "received_at"), System.currentTimeMillis());
        ClaimRequest request = new ClaimRequest(eventId, streamId, gen, receivedAt, str(fields, "channel"),
                str(fields, "thread_ts"));

        ClaimOutcome outcome;
        try {
            outcome = store.claim(request);
        } catch (RuntimeException e) {
            // 저장소 호출 자체가 실패하면 아무것도 쓰이지 않았다 — ACK하지 않고 메시지를 pending에 남긴다.
            log.error("claim 호출 실패 event_id={} stream_id={}", eventId, streamId, e);
            return;
        }

        if (outcome instanceof ClaimOutcome.Claimed claimed) {
            handleClaimed(record, fields, eventId, streamId, claimed);
        } else {
            log.info("선점 결과 event_id={} stream_id={} outcome={}", eventId, streamId, describe(outcome));
        }
    }

    private void handleClaimed(MapRecord<String, Object, Object> record, Map<Object, Object> fields, String eventId,
            String streamId, ClaimOutcome.Claimed claimed) {
        SlackMessageEvent event = SlackMessageEvent.fromQueueFields(fields);
        long startNanos = System.nanoTime();
        WorkerAttemptHandle handle = new WorkerAttemptHandle(eventId, claimed.attemptId(), startNanos, store);

        HandlingResult result;
        try {
            result = handler.handle(event, handle);
        } catch (Exception e) {
            // markSending 이전의 예외는 발신되지 않았음이 확실하니 Failed, 이후는 발신 여부를 알 수 없어 Unknown이다.
            String stage = "unexpected_exception:" + e.getClass().getSimpleName();
            log.error("처리 중 예상 못한 예외 event_id={} attempt_id={} sending_marked={}", eventId, claimed.attemptId(),
                    handle.sendingMarked(), e);
            result = handle.sendingMarked() ? new HandlingResult.Unknown(stage) : new HandlingResult.Failed(stage, false);
        }

        finalizeResult(streamId, eventId, claimed.attemptId(), result);
    }

    /**
     * M12 시점의 최소 정책(M13 이전): Delivered→COMPLETED, Unknown→UNKNOWN(복구 목록), Failed→DEAD(DLQ).
     * Rejected는 이미 소유권을 잃었다는 뜻이라 상태를 건드리지 않는다 — XAUTOCLAIM이 재확인한다.
     */
    private void finalizeResult(String streamId, String eventId, String attemptId, HandlingResult result) {
        boolean finalized = switch (result) {
            case HandlingResult.Delivered d -> store.finalizeAttempt(eventId, attemptId, streamId,
                    Finalization.completed(d.slackTs(), d.kind()));
            case HandlingResult.Unknown u -> store.finalizeAttempt(eventId, attemptId, streamId,
                    Finalization.unknown(kindFromStage(u.stage()), u.stage()));
            case HandlingResult.Failed f -> store.finalizeAttempt(eventId, attemptId, streamId,
                    Finalization.dead(kindFromStage(f.stage()), f.stage()));
            case HandlingResult.Rejected r -> false;
        };
        if (result instanceof HandlingResult.Rejected r) {
            log.warn("SENDING 거절— 상태 유지, ACK 안 함 event_id={} attempt_id={} reason={}", eventId, attemptId,
                    r.reason());
        } else if (!finalized) {
            // 저장 실패·소유권 상실 — ACK하지 않는다(B10). 재전달이 finalize를 다시 시도하게 둔다.
            log.warn("종료 기록 거절 또는 실패 — ACK 보류 event_id={} attempt_id={} result={}", eventId, attemptId,
                    result.getClass().getSimpleName());
        } else {
            log.info("이벤트 처리 완료 event_id={} attempt_id={} result={}", eventId, attemptId,
                    result.getClass().getSimpleName());
        }
    }

    private static String kindFromStage(String stage) {
        int i = stage.indexOf("_send:");
        return i > 0 ? stage.substring(0, i) : "unknown";
    }

    private static String describe(ClaimOutcome outcome) {
        return switch (outcome) {
            case ClaimOutcome.Busy b -> "BUSY";
            case ClaimOutcome.Settled s -> "SETTLED:" + s.reason();
            case ClaimOutcome.NoInput n -> "NO_INPUT";
            case ClaimOutcome.Claimed c -> "CLAIMED"; // handleClaimed()가 먼저 걸러내므로 여기 오지 않는다
        };
    }

    private static String str(Map<Object, Object> fields, String key) {
        Object v = fields.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isEmpty() ? null : s;
    }

    private static long parseLongOr(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
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
