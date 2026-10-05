package com.slack.lab.adapter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.AlertEvent;
import com.slack.lab.core.model.EvalQuestion;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 평가 질문 JSON을 읽는다. 알람형은 운영과 같은 모양의 메시지(고정 안내문 + 알람 블록)로, 멘션형은 질문 그대로 만든다 — 평가가
 * 운영의 질의 추출을 그대로 거치게 하려는 것이다.
 */
public final class EvalSetLoader {

    private EvalSetLoader() {}

    public static List<EvalQuestion> load(Path questionsJson, ObjectMapper mapper) throws IOException {
        JsonNode root = mapper.readTree(questionsJson.toFile());
        List<EvalQuestion> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : root.path("questions")) {
            String id = required(n, "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("질문 ID 중복: " + id);
            }
            String set = required(n, "set");
            if (!set.equals(EvalQuestion.FINAL) && !set.equals(EvalQuestion.TUNING)) {
                throw new IllegalArgumentException("알 수 없는 set: " + id + " " + set);
            }
            List<String> expected = new ArrayList<>();
            n.path("expected").forEach(e -> expected.add(e.asText()));
            switch (required(n, "kind")) {
                case "alarm" -> out.add(new EvalQuestion(id, set, EvalQuestion.Kind.ALARM, expected,
                        new AlertEvent("eval", id, required(n, "title"), required(n, "body"), "C-EVAL", Map.of())
                                .toMessageEvent().promptText()));
                case "mention" -> out.add(new EvalQuestion(id, set, EvalQuestion.Kind.MENTION, expected, required(n, "text")));
                default -> throw new IllegalArgumentException("알 수 없는 kind: " + id);
            }
        }
        return out;
    }

    private static String required(JsonNode n, String field) {
        String v = n.path(field).asText("");
        if (v.isBlank()) {
            throw new IllegalArgumentException("필수 필드 누락: " + field + " in " + n.path("id").asText("?"));
        }
        return v;
    }
}
