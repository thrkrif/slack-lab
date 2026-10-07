package com.slack.lab.adapter.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.model.RequestKind;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 분류 평가 질문 JSON을 읽는다. 질문은 전부 최상위 멘션 형태 텍스트 그대로다. */
public final class ClassificationSetLoader {

    private ClassificationSetLoader() {}

    public static List<ClassifyQuestion> load(Path json, ObjectMapper mapper) throws IOException {
        JsonNode root = mapper.readTree(json.toFile());
        List<ClassifyQuestion> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (JsonNode n : root.path("questions")) {
            String id = required(n, "id");
            if (!ids.add(id)) {
                throw new IllegalArgumentException("질문 ID 중복: " + id);
            }
            String set = required(n, "set");
            if (!set.equals(ClassifyQuestion.FINAL) && !set.equals(ClassifyQuestion.TUNING)) {
                throw new IllegalArgumentException("알 수 없는 set: " + id + " " + set);
            }
            RequestKind expected;
            try {
                expected = RequestKind.valueOf(required(n, "expected"));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("알 수 없는 expected: " + id, e);
            }
            out.add(new ClassifyQuestion(id, set, expected, required(n, "text")));
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
