package com.slack.lab.event;

/**
 * {@link SlackEventHandler}의 출력. M12부터 종료 상태 기록은 이 결과를 받은 워커가 한다 — 핸들러는
 * HTTP도 큐 ACK도 모르는 채로 무엇이 일어났는지만 보고한다(AGENTS.md 규칙 2).
 */
public sealed interface HandlingResult {

    /** @param kind "answer" 또는 "failure_notice" */
    record Delivered(String kind, String slackTs) implements HandlingResult {}

    /**
     * 전송되지 않았음이 확실한 실패.
     *
     * @param retryable 재시도 가능 여부. M13의 오류 분류가 아직 없어 지금은 항상 false다 — M13에서
     *     {@code SlackSendResult}·{@code LlmResult}에 분류를 더하면 이 값이 정교해진다.
     */
    record Failed(String stage, boolean retryable) implements HandlingResult {}

    /** 전송 여부를 확인할 수 없음. */
    record Unknown(String stage) implements HandlingResult {}

    /** SENDING 기록 자체가 거절됨(소유권 상실 등) — 발신을 시도하지 않았다. */
    record Rejected(String reason) implements HandlingResult {}
}
