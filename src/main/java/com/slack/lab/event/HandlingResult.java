package com.slack.lab.event;

/** {@link SlackEventHandler}의 출력. 컨트롤러는 이 값을 읽어 로그만 남기고 HTTP 응답은 그대로 200이다(§3.1). */
public sealed interface HandlingResult {

    /** @param kind "answer" 또는 "failure_notice" */
    record Delivered(String kind) implements HandlingResult {}

    /** 전송되지 않았음이 확실한 실패. */
    record Failed(String stage) implements HandlingResult {}

    /** 전송 여부를 확인할 수 없음. */
    record Unknown(String stage) implements HandlingResult {}

    /** SENDING 기록 자체가 거절됨(소유권 상실 등) — 발신을 시도하지 않았다. */
    record Rejected(String reason) implements HandlingResult {}
}
