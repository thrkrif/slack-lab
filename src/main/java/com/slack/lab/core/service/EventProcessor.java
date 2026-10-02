package com.slack.lab.core.service;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.StateProperties;
import com.slack.lab.config.WorkerProperties;
import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;
import com.slack.lab.core.model.HandlingResult;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.ProcessingStateStore;
import com.slack.lab.core.port.QueueDelivery;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 큐에서 꺼낸 메시지 하나를 M11 선점 결과표대로 처리하는 응용 서비스(PLAN 2단계 M12, M19에서 브로커 어댑터와 분리).
 * 선점 → 핸들러 호출 → 결과에 따른 종료 상태 기록 → <b>기록이 확인된 경우에만</b> 큐에서 제거한다(B10).
 * 어떤 브로커에서 왔는지 모르며 {@link QueueDelivery}와 {@link ProcessingStateStore} 포트만 쓴다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class EventProcessor {

    private static final Logger log = LoggerFactory.getLogger(EventProcessor.class);

    private final ProcessingStateStore store;
    private final SlackEventHandler handler;
    private final RetryPolicy retryPolicy;
    private final StateProperties stateProps;
    // 시도별 임대 갱신 작업. 동시에 여러 시도가 갱신을 스케줄하므로 스레드 1개면 한 시도의 지연이 다른 시도의
    // 갱신을 밀어낼 수 있어 처리 동시성만큼 둔다(MAJOR-1).
    private final ScheduledExecutorService scheduler;

    public EventProcessor(ProcessingStateStore store, SlackEventHandler handler, RetryPolicy retryPolicy,
            StateProperties stateProps, WorkerProperties workerProps) {
        this.store = store;
        this.handler = handler;
        this.retryPolicy = retryPolicy;
        this.stateProps = stateProps;
        this.scheduler = Executors.newScheduledThreadPool(Math.max(1, workerProps.concurrency() + 1), r -> {
            Thread t = new Thread(r, "lease-renewer");
            t.setDaemon(true);
            return t;
        });
    }

    @PreDestroy
    void stop() {
        scheduler.shutdownNow();
    }

    /** 메시지 하나를 끝까지 처리한다. 확정되지 않은 메시지는 acknowledge하지 않아 브로커가 다시 전달하게 둔다. */
    public void process(QueueDelivery delivery) {
        SlackMessageEvent event = delivery.event();
        String eventId = event.eventId();
        ClaimRequest request = new ClaimRequest(eventId, delivery.token(), delivery.gen(), delivery.receivedAtMs(),
                event.channel(), event.threadTs(), event);

        ClaimOutcome outcome;
        try {
            outcome = store.claim(request);
        } catch (RuntimeException e) {
            // 저장소 호출 자체가 실패하면 아무것도 쓰이지 않았다 — ACK하지 않고 메시지를 큐에 남긴다.
            log.error("claim 호출 실패 event_id={} delivery={}", eventId, delivery.token(), e);
            deferQuietly(delivery);
            return;
        }

        switch (outcome) {
            case ClaimOutcome.Claimed claimed -> handleClaimed(delivery, claimed);
            case ClaimOutcome.Settled settled -> {
                // 결과표가 이미 종료로 본 건(완료·이전 세대·창 초과·이상). 더 받을 이유가 없으니 확정한다.
                log.info("선점 결과 event_id={} delivery={} outcome=SETTLED:{}", eventId, delivery.token(),
                        settled.reason());
                acknowledgeQuietly(delivery);
            }
            case ClaimOutcome.Busy busy -> {
                // 다른 시도가 처리 중이다 — 확정하지 않고 나중에 다시 확인하게 놓아준다.
                log.info("선점 결과 event_id={} delivery={} outcome=BUSY", eventId, delivery.token());
                deferQuietly(delivery);
            }
            case ClaimOutcome.NoInput noInput -> {
                log.info("선점 결과 event_id={} delivery={} outcome=NO_INPUT", eventId, delivery.token());
                deferQuietly(delivery);
            }
        }
    }

    private void handleClaimed(QueueDelivery delivery, ClaimOutcome.Claimed claimed) {
        SlackMessageEvent event = delivery.event();
        String eventId = event.eventId();
        // 프로세스 간 구간(수신→선점)은 UTC epoch ms로 잰다. 시계가 어긋나면 음수가 나오고, 그 배치의 성능 측정은
        // 무효다(PLAN M17) — 지표 줄에 표시한다.
        long receivedAt = delivery.receivedAtMs();
        long queueWaitMs = System.currentTimeMillis() - receivedAt;
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

        boolean finalized;
        try {
            finalized = finalizeResult(delivery, claimed, result);
        } catch (RuntimeException e) {
            // 저장소 호출이 예외로 끝났다 — 기록 여부를 알 수 없으니 확정하지 않는다. 지표 줄은 남긴다.
            log.error("종료 기록 호출 실패 — ACK 보류 event_id={} attempt_id={}", eventId, claimed.attemptId(), e);
            finalized = false;
        }
        if (finalized) {
            acknowledgeQuietly(delivery);
        } else {
            deferQuietly(delivery);
        }
        logMetrics(eventId, claimed, result, finalized, handle, receivedAt, queueWaitMs, delivery.receivedAtMissing());
    }

    private void deferQuietly(QueueDelivery delivery) {
        try {
            delivery.defer();
        } catch (RuntimeException e) {
            log.warn("메시지 놓아주기(defer) 실패 event_id={} delivery={} reason={}", delivery.event().eventId(),
                    delivery.token(), e.getClass().getSimpleName());
        }
    }

    /**
     * 확정 뒤 큐에서 제거한다. 실패해도 상태는 이미 기록됐으므로 예외를 밖으로 내보내지 않는다 — 재전달되면 선점 결과표가
     * 종료로 판정해 다시 ACK한다. 여기서 터지면 성공한 시도의 지표 줄이 사라진다.
     */
    private void acknowledgeQuietly(QueueDelivery delivery) {
        try {
            delivery.acknowledge();
        } catch (RuntimeException e) {
            log.warn("확정 뒤 큐 제거(ACK) 실패 — 상태는 기록됨, 재전달 때 종료로 판정됨 event_id={} delivery={} reason={}",
                    delivery.event().eventId(), delivery.token(), e.getClass().getSimpleName());
        }
    }

    /**
     * 시도 하나의 지표를 한 줄로 남긴다(M17). 집계는 이 줄을 grep한다(scripts/p1-metrics).
     * {@code answer_ms}는 최초 수신부터 이 시도의 종료 기록까지이며 재시도·재처리 시도에서는 그 앞의 시도 시간까지 포함한다.
     */
    private void logMetrics(String eventId, ClaimOutcome.Claimed claimed, HandlingResult result, boolean finalized,
            WorkerAttemptHandle handle, long receivedAt, long queueWaitMs, boolean receivedAtMissing) {
        long answerMs = System.currentTimeMillis() - receivedAt;
        String kind = result instanceof HandlingResult.Delivered d ? d.kind() : "-";
        // gen>0·retries>0·manual_run은 앞선 시도와 재시도 대기가 구간에 섞인 시도다 — 집계는 첫 시도만 성능 표본으로 쓴다.
        log.info("처리 지표 event_id={} attempt_id={} gen={} retries={} manual_run={} result={} kind={} finalized={} "
                        + "queue_wait_ms={} llm_ms={} send_ms={} answer_ms={} received_at_missing={} negative_interval={}",
                eventId, claimed.attemptId(), claimed.gen(), claimed.retries(), claimed.manualRun(),
                result.getClass().getSimpleName(), kind, finalized, queueWaitMs, handle.phase("llm_ms"),
                handle.phase("send_ms"), answerMs, receivedAtMissing, queueWaitMs < 0 || answerMs < 0 || receivedAtMissing);
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
     * 잃었다는 뜻이라 상태를 건드리지 않는다 — 회수가 재확인한다.
     */
    private boolean finalizeResult(QueueDelivery delivery, ClaimOutcome.Claimed claimed, HandlingResult result) {
        String eventId = delivery.event().eventId();
        String attemptId = claimed.attemptId();
        String token = delivery.token();
        boolean finalized = switch (result) {
            case HandlingResult.Delivered d -> store.finalizeAttempt(eventId, attemptId, token,
                    Finalization.completed(d.slackTs(), d.kind()));
            case HandlingResult.Unknown u -> store.finalizeAttempt(eventId, attemptId, token,
                    Finalization.unknown(kindFromStage(u.stage()), u.stage()));
            case HandlingResult.Failed f -> store.finalizeAttempt(eventId, attemptId, token,
                    Finalization.dead(kindFromStage(f.stage()), f.stage()));
            case HandlingResult.RetryRequested r -> retryPolicy.scheduleRetry(eventId, attemptId, token,
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
        return finalized;
    }

    private static String kindFromStage(String stage) {
        int i = stage.indexOf("_send:");
        return i > 0 ? stage.substring(0, i) : "unknown";
    }
}
