package com.slack.lab.adapter.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.model.RequestKind;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

// 커밋된 분류 평가 세트가 규격(PLAN 4단계 M32)과 3단계 평가 세트와의 분리를 지키는지 고정한다.
class ClassificationSetTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Path DIR = Path.of("docs/rag-eval");

    private static List<ClassifyQuestion> load() throws Exception {
        return ClassificationSetLoader.load(DIR.resolve("classification.json"), MAPPER);
    }

    private static long count(List<ClassifyQuestion> qs, String set, RequestKind kind) {
        return qs.stream().filter(q -> q.set().equals(set) && q.expected() == kind).count();
    }

    @Test
    void 규격_tuning_30_final_60() throws Exception {
        List<ClassifyQuestion> qs = load();
        for (RequestKind k : RequestKind.values()) {
            assertThat(count(qs, ClassifyQuestion.TUNING, k)).as("tuning " + k).isEqualTo(10);
            assertThat(count(qs, ClassifyQuestion.FINAL, k)).as("final " + k).isEqualTo(20);
        }
    }

    @Test
    void 질문_텍스트는_모두_다르고_세트가_겹치지_않는다() throws Exception {
        List<ClassifyQuestion> qs = load();
        assertThat(qs.stream().map(ClassifyQuestion::text).collect(Collectors.toSet())).hasSameSizeAs(qs);
    }

    @Test
    void 보강_tuning_세트는_tuning이고_다른_세트와_질문이_겹치지_않는다() throws Exception {
        List<ClassifyQuestion> extra = ClassificationSetLoader.load(DIR.resolve("classification-tuning-extra.json"), MAPPER);
        assertThat(extra).isNotEmpty().allSatisfy(q -> assertThat(q.set()).isEqualTo(ClassifyQuestion.TUNING));
        assertThat(extra.stream().map(ClassifyQuestion::id).collect(Collectors.toSet())).hasSameSizeAs(extra);
        Set<String> main = load().stream().map(ClassifyQuestion::text).collect(Collectors.toSet());
        Set<String> rag = new HashSet<>();
        for (JsonNode n : MAPPER.readTree(DIR.resolve("questions.json").toFile()).path("questions")) {
            rag.add(n.path("text").asText(""));
        }
        for (ClassifyQuestion q : extra) {
            assertThat(main).as(q.id()).doesNotContain(q.text());
            assertThat(rag).as(q.id()).doesNotContain(q.text());
        }
    }

    @Test
    void 이전_3단계_평가_질문과_겹치지_않는다() throws Exception {
        Set<String> rag = new HashSet<>();
        for (JsonNode n : MAPPER.readTree(DIR.resolve("questions.json").toFile()).path("questions")) {
            rag.add(n.path("text").asText(""));
            rag.add(n.path("title").asText(""));
        }
        for (ClassifyQuestion q : load()) {
            assertThat(rag).as(q.id()).doesNotContain(q.text());
        }
    }
}
