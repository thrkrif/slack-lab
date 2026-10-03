package com.slack.lab.core.model;

/**
 * 실패 원인의 종류. 어느 단계에서 났는지({@link ProcessingStage})나 어떤 메시지였는지({@link MessageKind})는 담지 않는다 —
 * 조합이 늘어나기 때문이다. 외부 시스템이 준 상세(Slack 오류 코드, HTTP 상태, 예외 클래스)는 {@link ErrorInfo#detail()}에
 * 그대로 두고 여기서는 분류만 한다. 재시도 가능 여부와 결과 불명 여부는 오류의 성격이 아니라 호출 결과 타입
 * ({@code Failed.retryable}, {@code Unknown})이 정한다.
 *
 * <p>{@link #code()}는 DB·로그에 남는 고정 값이다. 자바 이름을 바꿔도 저장값은 바뀌지 않게 따로 둔다.
 */
public enum ErrorCode {
    UNEXPECTED_EXCEPTION("unexpected_exception"),

    LLM_TIMEOUT("llm_timeout"),
    LLM_LANGUAGE_VIOLATION("llm_language_violation"),
    LLM_NO_BUDGET("llm_no_budget"),
    LLM_REQUEST_BUILD_FAILED("llm_request_build_failed"),
    LLM_CONNECT_FAILED("llm_connect_failed"),
    LLM_REQUEST_FAILED("llm_request_failed"),
    LLM_HTTP_ERROR("llm_http_error"),
    LLM_RESPONSE_INVALID("llm_response_invalid"),

    SLACK_NO_BUDGET("slack_no_budget"),
    SLACK_REQUEST_BUILD_FAILED("slack_request_build_failed"),
    SLACK_SUBMIT_FAILED("slack_submit_failed"),
    SLACK_CONNECT_FAILED("slack_connect_failed"),
    SLACK_RATE_LIMITED("slack_rate_limited"),
    SLACK_HTTP_ERROR("slack_http_error"),
    SLACK_API_ERROR("slack_api_error"),
    SLACK_TIMEOUT("slack_timeout"),
    SLACK_RESPONSE_INVALID("slack_response_invalid"),
    SLACK_CONNECTION_LOST("slack_connection_lost"),
    SLACK_INTERRUPTED("slack_interrupted"),

    QUEUE_PUBLISH_FAILED("queue_publish_failed"),
    QUEUE_UNROUTABLE("queue_unroutable"),
    QUEUE_NACKED("queue_nacked"),
    QUEUE_CONFIRM_TIMEOUT("queue_confirm_timeout"),
    QUEUE_CONFIRM_INTERRUPTED("queue_confirm_interrupted"),

    /** 저장소가 스스로 판단해 기록하는 원인(임대 만료, 24시간 창, 수동 시도 유실, 깨진 입력, 소유권 상실). */
    SENDING_LEASE_EXPIRED("sending_lease_expired"),
    WINDOW_EXPIRED("window_expired"),
    MANUAL_ATTEMPT_LOST("manual_attempt_lost"),
    CORRUPT_PAYLOAD("corrupt_payload"),
    GEN_ANOMALY("gen_anomaly"),
    /** 임대가 끊긴 이전 시도의 입력을 보존한다(상세: 그때 상태). */
    RECOVERED_STALE("recovered_stale"),
    SEND_REJECTED("send_rejected");

    private final String code;

    ErrorCode(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
