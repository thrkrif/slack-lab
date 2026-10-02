package com.slack.lab.core.service;

import com.slack.lab.config.RetryProperties;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.core.port.ProcessingStateStore;
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
    public boolean scheduleRetry(String eventId, String attemptId, String deliveryToken, long currentGen,
            int currentRetries, long retryAfterMsOverride, String stage) {
        // StartupInvariants(M10)가 backoffMs.size() >= maxRetries를 기동 시 검사하지만, 그 검사를 거치지
        // 않는 조합(단위 테스트가 빈을 직접 생성하는 경우 등)이면 여기서도 벗어날 수 있다(code-reviewer가
        // retry.max-retries=4·기본 backoffMs 3개 조합으로 실제 재현). 불변식이 지켜졌어도 설정 실수가
        // 크래시로 이어지지 않도록 방어적으로 clamp한다 — 규칙 4 "조용한 실패" 방지와는 반대 방향이지만,
        // 여기서는 예외로 처리를 통째로 끊기보다 마지막 백오프 값으로 재시도를 이어가는 쪽이 안전하다.
        int backoffIndex = Math.min(currentRetries, props.backoffMs().size() - 1);
        long backoffMs = retryAfterMsOverride > 0 ? retryAfterMsOverride : props.backoffMs().get(backoffIndex);
        long retryAtMs = System.currentTimeMillis() + backoffMs;
        int retries = currentRetries + 1;
        return store.scheduleRetry(eventId, attemptId, deliveryToken, currentGen + 1, retryAtMs, retries, stage);
    }
}
