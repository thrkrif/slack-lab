package com.slack.lab.adapter.postgres;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.SlackMessageEvent;

/** 보존하는 입력(이벤트 + 세대 + 최초 수신 시각)의 JSON 표현. 재투입·재처리가 이 값으로 메시지를 다시 만든다. */
final class PreservedInputJson {

    private PreservedInputJson() {}

    static String write(ObjectMapper mapper, SlackMessageEvent e, long gen, long receivedAtMs) {
        var node = mapper.createObjectNode();
        node.put("event_id", e.eventId());
        node.put("channel", e.channel());
        node.put("user", e.user());
        node.put("text", e.text());
        node.put("ts", e.ts());
        node.put("thread_ts", e.threadTs());
        node.put("gen", gen);
        node.put("received_at", receivedAtMs);
        try {
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("입력 직렬화 실패", ex);
        }
    }

    /** 같은 입력을 다른 세대로 다시 쓴다(재시도 예약: gen+1). */
    static String withGen(ObjectMapper mapper, String json, long gen) {
        try {
            var node = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(json);
            node.put("gen", gen);
            return mapper.writeValueAsString(node);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("입력 역직렬화 실패", ex);
        }
    }

    static SlackMessageEvent readEvent(ObjectMapper mapper, String json) {
        try {
            JsonNode n = mapper.readTree(json);
            return new SlackMessageEvent(text(n, "event_id"), text(n, "channel"), text(n, "user"), text(n, "text"),
                    text(n, "ts"), text(n, "thread_ts"), null, null, null);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("입력 역직렬화 실패", ex);
        }
    }

    static long readGen(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json).path("gen").asLong(0);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("입력 역직렬화 실패", ex);
        }
    }

    static long readReceivedAt(ObjectMapper mapper, String json) {
        try {
            return mapper.readTree(json).path("received_at").asLong(0);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("입력 역직렬화 실패", ex);
        }
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isNull() || v.isMissingNode() ? null : v.asText();
    }
}
