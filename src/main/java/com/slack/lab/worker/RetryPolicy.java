package com.slack.lab.worker;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.state.ProcessingStateStore;
import org.springframework.stereotype.Component;

/**
 * 재시도 대기 시간을 계산하고 저장소에 예약한다(M13, PLAN "M13 재시도·DLQ"). 백오프 계산(정책)과 원자적
 * 기록(저장소)을 분리해, {@code state.lua}의 전이 규약을 몰라도 워커가 재시도 여부만 물을 수 있게 한다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class RetryPolicy {

    private final RetryProperties props;
    private final ProcessingStateStore store;

    public RetryPolicy(RetryProperties props, ProcessingStateStore store) {
        this.props = props;
        this.store = store;
    }

    /** {@code retries >= retry.max-retries}면(또는 수동 승인 실행이면) 이번이 마지막 시도다 — 더 재시도하지 않는다. */
    public boolean isFinalAttempt(int retries, boolean manualRun) {
        return manualRun || retries >= props.maxRetries();
    }

    /**
     * 재시도를 예약한다. 성공하면 메시지가 ACK되고 세대가 {@code currentGen + 1}로 올라간다.
     *
     * @param retryAfterMsOverride 0보다 크면 정책의 기본 백오프 대신 이 값을 쓴다(Slack 429 Retry-After 등).
     */
    public boolean scheduleRetry(String eventId, String attemptId, String streamId, long currentGen,
            int currentRetries, long retryAfterMsOverride, String stage) {
        long backoffMs = retryAfterMsOverride > 0 ? retryAfterMsOverride : props.backoffMs().get(currentRetries);
        long retryAtMs = System.currentTimeMillis() + backoffMs;
        int retries = currentRetries + 1;
        return store.scheduleRetry(eventId, attemptId, streamId, currentGen + 1, retryAtMs, retries, stage);
    }
}
