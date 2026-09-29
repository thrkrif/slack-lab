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
import com.slack.lab.state.StateProperties;
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
import java.util.concurrent.ScheduledFuture;
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
    private final StateProperties stateProps;
    private final ProcessingStateStore store;
    private final SlackEventHandler handler;
    private final RetryPolicy retryPolicy;

    private ExecutorService pool;
    // reclaim 주기 작업과 시도별 임대 갱신 작업을 함께 돌린다(MAJOR-1) — 동시에 여러 시도가 갱신을 스케줄하므로
    // 스레드 1개짜리 스케줄러면 한 시도의 지연이 다른 시도의 갱신을 밀어낼 수 있어, concurrency+1개로 둔다.
    private ScheduledExecutorService scheduler;
    private volatile boolean running;

    public EventWorker(StringRedisTemplate redis, QueueProperties queueProps, WorkerProperties workerProps,
            StateProperties stateProps, ProcessingStateStore store, SlackEventHandler handler,
            RetryPolicy retryPolicy) {
        this.redis = redis;
        this.queueProps = queueProps;
        this.workerProps = workerProps;
        this.stateProps = stateProps;
        this.store = store;
        this.handler = handler;
        this.retryPolicy = retryPolicy;
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
        scheduler = Executors.newScheduledThreadPool(workerProps.concurrency() + 1, daemonThreadFactory());
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
        boolean finalAttempt = retryPolicy.isFinalAttempt(claimed.retries(), claimed.manualRun());

        // MAJOR-1: 임대(state.lease-ms)를 주기적으로 갱신하지 않으면 LLM+지연 합이 임대를 넘는 처리는 전부
        // markSending()에서 거절돼 조용한 실패가 된다(ARCHITECTURE §3.2). 처리 시작부터 끝까지 갱신을 돌린다.
        ScheduledFuture<?> renewal = scheduler.scheduleWithFixedDelay(
                () -> renewLease(eventId, claimed.attemptId(), handle), stateProps.renewMs(), stateProps.renewMs(),
                TimeUnit.MILLISECONDS);

        HandlingResult result;
        try {
            result = handler.handle(event, handle, finalAttempt);
        } catch (Exception e) {
            // markSending 이전의 예외는 발신되지 않았음이 확실하니 Failed, 이후는 발신 여부를 알 수 없어 Unknown이다.
            String stage = "unexpected_exception:" + e.getClass().getSimpleName();
            log.error("처리 중 예상 못한 예외 event_id={} attempt_id={} sending_marked={}", eventId, claimed.attemptId(),
                    handle.sendingMarked(), e);
            result = handle.sendingMarked() ? new HandlingResult.Unknown(stage) : new HandlingResult.Failed(stage, false);
        } finally {
            renewal.cancel(false);
        }

        finalizeResult(streamId, eventId, claimed, result);
    }

    /** 임대 갱신 실패(소유권 상실)를 핸들에 반영해, 이후 markSending()이 저장소를 다시 부르지 않고 거절하게 한다. */
    private void renewLease(String eventId, String attemptId, WorkerAttemptHandle handle) {
        try {
            boolean ok = store.renew(eventId, attemptId);
            if (!ok) {
                handle.markOwnershipLost();
                log.warn("임대 갱신 실패 — 소유권 상실 event_id={} attempt_id={}", eventId, attemptId);
            }
        } catch (RuntimeException e) {
            log.error("임대 갱신 중 오류 event_id={} attempt_id={}", eventId, attemptId, e);
        }
    }

    /**
     * M13 전이표: Delivered→COMPLETED(안내 성공도 포함), Unknown→UNKNOWN(복구 목록, 재발신 없음),
     * Failed→DEAD(DLQ), RetryRequested→RETRY_WAIT(안내 없이 재시도 예약). Rejected는 이미 소유권을
     * 잃었다는 뜻이라 상태를 건드리지 않는다 — XAUTOCLAIM이 재확인한다.
     */
    private void finalizeResult(String streamId, String eventId, ClaimOutcome.Claimed claimed, HandlingResult result) {
        String attemptId = claimed.attemptId();
        boolean finalized = switch (result) {
            case HandlingResult.Delivered d -> store.finalizeAttempt(eventId, attemptId, streamId,
                    Finalization.completed(d.slackTs(), d.kind()));
            case HandlingResult.Unknown u -> store.finalizeAttempt(eventId, attemptId, streamId,
                    Finalization.unknown(kindFromStage(u.stage()), u.stage()));
            case HandlingResult.Failed f -> store.finalizeAttempt(eventId, attemptId, streamId,
                    Finalization.dead(kindFromStage(f.stage()), f.stage()));
            case HandlingResult.RetryRequested r -> retryPolicy.scheduleRetry(eventId, attemptId, streamId,
                    claimed.gen(), claimed.retries(), r.retryAfterMsOverride(), r.stage());
            case HandlingResult.Rejected r -> false;
        };
        if (result instanceof HandlingResult.Rejected r) {
            log.warn("SENDING 거절— 상태 유지, ACK 안 함 event_id={} attempt_id={} reason={}", eventId, attemptId,
                    r.reason());
        } else if (!finalized) {
            // 저장 실패·소유권 상실 — ACK하지 않는다(B10). 재전달이 finalize/retry를 다시 시도하게 둔다.
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
