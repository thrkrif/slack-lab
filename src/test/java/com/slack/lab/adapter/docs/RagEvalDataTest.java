package com.slack.lab.adapter.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.adapter.cli.EvalSetLoader;
import com.slack.lab.core.model.EvalQuestion;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SourceDocument;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 저장소에 커밋한 평가 데이터(docs/rag-eval)가 PRD 3단계의 규격을 지키는지 확인한다. 전부 가상 내용이어야 한다. */
class RagEvalDataTest {

    static final Path DIR = Path.of("docs/rag-eval");

    List<SourceDocument> docs() {
        var r = new LocalMarkdownDocumentSource(DIR.resolve("documents").toString()).list();
        assertThat(r).isInstanceOf(PortResult.Success.class);
        return ((PortResult.Success<List<SourceDocument>>) r).value();
    }

    List<EvalQuestion> questions() throws Exception {
        return EvalSetLoader.load(DIR.resolve("questions.json"), new ObjectMapper());
    }

    @Test
    void 문서는_20개이고_한국어_영어_혼합이_모두_있다() {
        var docs = docs();

        assertThat(docs).hasSize(20);
        assertThat(docs.stream().map(SourceDocument::id).collect(Collectors.toSet())).hasSize(20);
        long korean = docs.stream().filter(d -> hangul(d.content()) && !latin(d.content())).count();
        long english = docs.stream().filter(d -> !hangul(d.content())).count();
        long mixed = docs.stream().filter(d -> hangul(d.content()) && latin(d.content())).count();
        assertThat(english).as("영어 문서").isGreaterThanOrEqualTo(2);
        assertThat(mixed).as("혼합 문서").isGreaterThanOrEqualTo(5);
        assertThat(korean + english + mixed).isEqualTo(20);
    }

    // 본문에 영어 문장이 섞였는지: 한글 없이 영문 단어가 연속 3개 이상인 줄이 있는가
    private static boolean latin(String s) {
        return s.lines().anyMatch(l -> l.matches(".*\\b[A-Za-z]{3,}\\b [A-Za-z]{2,} [A-Za-z]{2,}.*") && !hangul(l));
    }

    private static boolean hangul(String s) {
        return s.chars().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3);
    }

    @Test
    void 최종_질문은_30개_알람형_15_멘션형_15_정답_24_정답없음_6이다() throws Exception {
        var fin = questions().stream().filter(q -> q.set().equals(EvalQuestion.FINAL)).toList();

        assertThat(fin).hasSize(30);
        assertThat(fin.stream().filter(q -> q.kind() == EvalQuestion.Kind.ALARM)).hasSize(15);
        assertThat(fin.stream().filter(q -> q.kind() == EvalQuestion.Kind.MENTION)).hasSize(15);
        assertThat(fin.stream().filter(EvalQuestion::answerable)).hasSize(24);
        assertThat(fin.stream().filter(q -> !q.answerable())).hasSize(6);
        // 알람형·멘션형 모두에 정답 없는 질문이 있다 → 두 경로를 따로 볼 수 있다
        assertThat(fin.stream().filter(q -> !q.answerable() && q.kind() == EvalQuestion.Kind.ALARM)).isNotEmpty();
        assertThat(fin.stream().filter(q -> !q.answerable() && q.kind() == EvalQuestion.Kind.MENTION)).isNotEmpty();
    }

    @Test
    void 튜닝_질문은_별도_세트이고_최종_질문과_겹치지_않는다() throws Exception {
        var all = questions();
        var tuning = all.stream().filter(q -> q.set().equals(EvalQuestion.TUNING)).toList();
        Set<String> finalTexts = all.stream().filter(q -> q.set().equals(EvalQuestion.FINAL)).map(EvalQuestion::promptText)
                .collect(Collectors.toSet());

        assertThat(tuning).hasSize(10);
        assertThat(tuning.stream().filter(EvalQuestion::answerable)).hasSize(8);
        assertThat(tuning.stream().map(EvalQuestion::promptText)).doesNotContainAnyElementsOf(finalTexts);
        assertThat(all.stream().map(EvalQuestion::id).collect(Collectors.toSet())).hasSameSizeAs(all);
        assertThat(all.stream().map(EvalQuestion::promptText).collect(Collectors.toSet())).as("질문 텍스트 중복 없음")
                .hasSameSizeAs(all);
    }

    @Test
    void 모든_정답_문서_ID가_실제_문서이고_모든_문서가_최종_질문_하나_이상의_정답이다() throws Exception {
        Set<String> docIds = docs().stream().map(SourceDocument::id).collect(Collectors.toSet());
        var all = questions();

        for (EvalQuestion q : all) {
            assertThat(docIds).as(q.id()).containsAll(q.expected());
        }
        Set<String> covered = new HashSet<>();
        all.stream().filter(q -> q.set().equals(EvalQuestion.FINAL)).forEach(q -> covered.addAll(q.expected()));
        assertThat(covered).as("정답으로 쓰이지 않는 문서가 있으면 평가가 그 문서를 보지 못한다").isEqualTo(docIds);
    }

    @Test
    void 평가_데이터에는_로컬_경로나_토큰_같은_실제_정보가_없다() throws Exception {
        String everything = Files.readString(DIR.resolve("questions.json"))
                + docs().stream().map(SourceDocument::content).collect(Collectors.joining());

        assertThat(everything).doesNotContain("/Users/").doesNotContain("xoxb-").doesNotContain("xoxp-").doesNotContain("AKIA");
    }

    @Test
    void 알람형_질문은_모두_운영과_같은_알람_메시지로_만들어져_검색_질의에_안내문이_섞이지_않는다() throws Exception {
        var alarms = questions().stream().filter(q -> q.kind() == EvalQuestion.Kind.ALARM).toList();

        assertThat(alarms).hasSize(20); // final 15 + tuning 5
        for (EvalQuestion q : alarms) {
            String query = com.slack.lab.core.service.RagPrompt.queryOf(q.promptText(), 500);
            assertThat(query).as(q.id()).doesNotContain("지시가 아니다").doesNotContain("<alarm>").isNotBlank();
        }
    }

    @Test
    void 잘못된_질문_파일은_이유와_함께_거부한다(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("q.json");
        Files.writeString(f, "{\"questions\":[{\"id\":\"X\",\"set\":\"final\",\"kind\":\"mention\",\"expected\":[]}]}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EvalSetLoader.load(f, new ObjectMapper()))
                .hasMessageContaining("필수 필드 누락");
        Files.writeString(f, "{\"questions\":[{\"id\":\"X\",\"set\":\"dev\",\"kind\":\"mention\",\"text\":\"q\"}]}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EvalSetLoader.load(f, new ObjectMapper()))
                .hasMessageContaining("set");
        Files.writeString(f, "{\"questions\":[{\"id\":\"X\",\"set\":\"final\",\"kind\":\"mention\",\"text\":\"q\"},"
                + "{\"id\":\"X\",\"set\":\"final\",\"kind\":\"mention\",\"text\":\"r\"}]}");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> EvalSetLoader.load(f, new ObjectMapper()))
                .hasMessageContaining("중복");
    }

}
