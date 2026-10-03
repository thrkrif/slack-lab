package com.slack.lab.core.model;

/**
 * {@link SlackEventHandler}의 출력. M12부터 종료 상태 기록은 이 결과를 받은 워커가 한다 — 핸들러는
 * HTTP도 큐 ACK도 모르는 채로 무엇이 일어났는지만 보고한다(AGENTS.md 규칙 2).
 */
public sealed interface HandlingResult {

    record Delivered(MessageKind kind, String slackTs) implements HandlingResult {}

    /**
     * 전송되지 않았음이 확실한 실패. M13부터 워커는 이 결과를 항상 DEAD+DLQ로 종료한다 — 재시도 가능한
     * 오류는 {@link RetryRequested}로 갈리기 때문에, 여기 도달했다는 것 자체가 "더 시도하지 않는다"는 뜻이다.
     */
    record Failed(Failure failure) implements HandlingResult {}

    /** 전송 여부를 확인할 수 없음. 자동 재발신 대상이 아니다 — 복구 목록으로 간다. */
    record Unknown(Failure failure) implements HandlingResult {}

    /** SENDING 기록 자체가 거절됨(소유권 상실 등) — 발신을 시도하지 않았다. */
    record Rejected(ErrorInfo error) implements HandlingResult {}

    /**
     * 재시도 가능한 오류이고 마지막 시도가 아니다(M13). 안내를 보내지 않고 워커가 {@code RETRY_WAIT(gen+1)}로
     * 예약한다.
     *
     * @param retryAfterMsOverride Slack 429 {@code Retry-After}처럼 재시도 정책의 기본 대기를 대체할 값(ms).
     *     0이면 정책의 기본 백오프를 쓴다.
     */
    record RetryRequested(Failure failure, long retryAfterMsOverride) implements HandlingResult {}
}
