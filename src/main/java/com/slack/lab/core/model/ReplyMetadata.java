package com.slack.lab.core.model;

/**
 * 봇 답글에 붙이는 Slack 메시지 메타데이터(M14). 발신 결과가 불명일 때 {@code recovery check}가 스레드에서
 * 이 값으로 "그 시도가 실제로 보낸 답글"을 찾는다. 본문·프롬프트는 담지 않는다.
 */
public record ReplyMetadata(String eventId, String attemptId) {

    public static final String EVENT_TYPE = "slack_lab_reply";
}
