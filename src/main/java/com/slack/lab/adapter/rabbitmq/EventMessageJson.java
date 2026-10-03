package com.slack.lab.adapter.rabbitmq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.SlackMessageEvent;
import java.io.IOException;

/** 큐 메시지 본문(JSON). 이벤트 + 세대 + 최초 수신 시각 + 재전송 횟수. 브로커 헤더가 아니라 본문에 두어 어떤 소비자도 읽는다. */
final class EventMessageJson {

    private EventMessageJson() {}

    static byte[] write(ObjectMapper mapper, SlackMessageEvent e, long gen, long receivedAtMs, String retryNum) {
        var node = mapper.createObjectNode();
        node.put("schema_version", 1);
        node.put("event_id", e.eventId());
        node.put("gen", gen);
        node.put("received_at", receivedAtMs);
        node.put("channel", e.channel());
        node.put("ts", e.ts());
        node.put("thread_ts", e.threadTs() == null ? "" : e.threadTs());
        node.put("user", e.user() == null ? "" : e.user());
        node.put("text", e.text() == null ? "" : e.text());
        node.put("retry_num", retryNum == null ? "" : retryNum);
        try {
            return mapper.writeValueAsBytes(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("메시지 직렬화 실패", ex);
        }
    }

    /** 읽은 결과. event_id가 없거나 깨진 본문이면 null이다(독약 메시지 — 호출자가 데드레터로 보낸다). */
    record Parsed(SlackMessageEvent event, long gen, long receivedAtMs, boolean receivedAtMissing) {}

    static Parsed read(ObjectMapper mapper, byte[] body) {
        try {
            JsonNode n = mapper.readTree(body);
            String eventId = text(n, "event_id");
            if (eventId == null) {
                return null;
            }
            SlackMessageEvent event = new SlackMessageEvent(eventId, text(n, "channel"), text(n, "user"),
                    text(n, "text"), text(n, "ts"), text(n, "thread_ts"), null, null, null);
            long received = n.path("received_at").asLong(-1);
            boolean missing = received < 0;
            return new Parsed(event, n.path("gen").asLong(0), missing ? System.currentTimeMillis() : received, missing);
        } catch (IOException ex) {
            return null;
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isEmpty() ? null : v.asText();
    }
}
