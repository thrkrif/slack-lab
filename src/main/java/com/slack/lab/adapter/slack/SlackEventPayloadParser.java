package com.slack.lab.adapter.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.slack.lab.core.model.SlackMessageEvent;

/** Slack {@code event_callback} 페이로드(JSON)를 코어의 {@link SlackMessageEvent}로 바꾼다. 코어는 JSON을 모른다. */
public final class SlackEventPayloadParser {

    private SlackEventPayloadParser() {}

    /** 필수 값(event_id·channel·ts)이 없으면 IllegalArgumentException. 호출자가 400으로 바꾼다. */
    public static SlackMessageEvent parse(JsonNode root) {
        JsonNode ev = root.path("event");
        return new SlackMessageEvent(
                required(root, "event_id"),
                required(ev, "channel"),
                text(ev, "user"),
                text(ev, "text"),
                required(ev, "ts"),
                text(ev, "thread_ts"),
                text(ev, "bot_id"),
                text(ev, "subtype"),
                // 어떤 멘션 토큰이 봇 자신인지 알려 주는 값. 페이로드에 없으면 null이다.
                text(root.path("authorizations").path(0), "user_id"));
    }

    private static String required(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) {
            throw new IllegalArgumentException("필수 필드 누락: " + field);
        }
        return v;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isEmpty() ? v.asText() : null;
    }
}
