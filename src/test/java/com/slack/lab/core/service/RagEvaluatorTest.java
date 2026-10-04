package com.slack.lab.core.service;

import static com.slack.lab.core.service.RagTestFakes.hit;
import static com.slack.lab.core.service.RagTestFakes.okEmbedding;
import static com.slack.lab.core.service.RagTestFakes.store;
import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.EvalQuestion;
import com.slack.lab.core.model.EvalReport;
import com.slack.lab.core.model.SearchResult;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RagEvaluatorTest {

    static EvalQuestion q(String id, String set, String... expected) {
        return new EvalQuestion(id, set, EvalQuestion.Kind.MENTION, List.of(expected), "질문 " + id);
    }

    /** 질문 텍스트("질문 <id>")로 미리 정해 둔 검색 결과를 돌려주는 서비스. */
    static RetrievalService scripted(java.util.Map<String, SearchResult> byId, double minScore) {
        var current = new java.util.concurrent.atomic.AtomicReference<String>();
        var embedding = com.slack.lab.core.service.RagTestFakes.embedding(text -> {
            current.set(text.substring("질문 ".length()));
            return new com.slack.lab.core.model.EmbeddingResult.Success(new float[] {1, 0}, 1);
        });
        var store = store((v, ms) -> byId.get(current.get()));
        return new RetrievalService(embedding, store, new RetrievalService.Config(5_000, 3, minScore, 3_000, 500));
    }

    static SearchResult hits(Object... idScore) {
        List<com.slack.lab.core.model.DocumentHit> list = new ArrayList<>();
        for (int i = 0; i < idScore.length; i += 2) {
            list.add(hit((String) idScore[i], "t", "본문 " + idScore[i], (Double) idScore[i + 1]));
        }
        return new SearchResult.Success(list, 1);
    }

    @Test
    void 정답이_순위_상위에_있으면_hit이고_임계값_미달이어도_hit으로_센다() {
        var svc = scripted(java.util.Map.of("A", hits("X", 0.9, "DOC-A", 0.3)), 0.5); // 정답은 2위, 임계값 미달
        var report = new RagEvaluator(svc, 3).evaluate(List.of(q("A", "final", "DOC-A")));

        var s = report.set("final");
        assertThat(s.hits()).as("hit@3은 임계값 적용 전 순위로 잰다").isEqualTo(1);
        assertThat(s.extraInjected()).as("주입된 X는 정답이 아니다").isEqualTo(1);
        assertThat(s.rows().get(0).injected()).containsExactly("X");
    }

    @Test
    void 정답이_K위_밖이면_miss이고_같은_문서의_여러_청크는_한_문서로_센다() {
        var svc = scripted(java.util.Map.of("A", hits("X", 0.9, "X", 0.8, "Y", 0.7)), 0.0);
        var report = new RagEvaluator(svc, 2).evaluate(List.of(q("A", "final", "Y")));

        // 청크 순위 [X,X,Y]는 문서 순위 [X,Y] — K=2 안에 Y가 있다
        assertThat(report.set("final").hits()).isEqualTo(1);
        var report1 = new RagEvaluator(svc, 1).evaluate(List.of(q("A", "final", "Y")));
        assertThat(report1.set("final").hits()).isZero();
    }

    @Test
    void 정답_없는_질문은_임계값_뒤_주입이_0건이어야_근거_미주입이다() {
        var svc = scripted(java.util.Map.of("N1", hits("X", 0.2), "N2", hits("X", 0.7)), 0.5);
        var report = new RagEvaluator(svc, 3).evaluate(List.of(q("N1", "final"), q("N2", "final")));

        var s = report.set("final");
        assertThat(s.none()).isEqualTo(2);
        assertThat(s.noInjected()).isEqualTo(1);
        assertThat(s.rows().get(0).noInjection()).isTrue();
        assertThat(s.rows().get(1).noInjection()).isFalse();
    }

    @Test
    void 검색_불가는_판정에서_실패로_세고_불합격이다() {
        var svc = scripted(java.util.Map.of("A", new SearchResult.Failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED), 1)), 0.5);
        var report = new RagEvaluator(svc, 3).evaluate(List.of(q("A", "final", "DOC-A")));

        assertThat(report.set("final").unavailable()).isEqualTo(1);
        assertThat(report.set("final").passes()).isFalse();
        assertThat(report.anyUnavailable()).isTrue();
        assertThat(report.summary()).contains("UNAVAILABLE");
    }

    @Test
    void 합격선_경계_정답_24중_20은_합격_19는_불합격_없음_6중_5는_합격_4는_불합격() {
        for (int hits : new int[] {20, 19}) {
            for (int noInj : new int[] {5, 4}) {
                var r = new EvalReport.SetResult("final", 24, hits, 6, noInj, 0, 0, List.of());
                assertThat(r.passes()).as("hit=%d noInjected=%d", hits, noInj).isEqualTo(hits == 20 && noInj == 5);
            }
        }
        assertThat(new EvalReport.SetResult("final", 24, 24, 6, 6, 1, 0, List.of()).passes()).as("검색 불가가 있으면 불합격").isFalse();
    }

    @Test
    void 튜닝_세트는_판정하지_않고_세트별로_따로_센다() {
        var svc = scripted(java.util.Map.of("F", hits("DOC-F", 0.9), "T", hits("OTHER", 0.9)), 0.5);
        var report = new RagEvaluator(svc, 3).evaluate(List.of(q("F", "final", "DOC-F"), q("T", "tuning", "DOC-T")));

        assertThat(report.set("final").hits()).isEqualTo(1);
        assertThat(report.set("tuning").hits()).isZero();
        assertThat(report.summary()).contains("== final (합격 판정)").contains("== tuning (조정용, 판정 아님)");
        assertThat(report.summary().split("판정: ").length).as("판정 줄은 최종 세트에만").isEqualTo(2);
    }

    @Test
    void 요약에는_질문_ID_문서_ID_점수만_있고_문서_본문은_없다() {
        var svc = scripted(java.util.Map.of("A", hits("DOC-A", 0.91)), 0.5);

        String summary = new RagEvaluator(svc, 3).evaluate(List.of(q("A", "final", "DOC-A"))).summary();

        assertThat(summary).contains("DOC-A:0.91").doesNotContain("본문");
    }

    @Test
    void 답변_쌍_표_양식은_질문마다_한_줄이고_정답_없음을_표시한다() {
        String t = RagEvaluator.pairsTemplate(List.of(q("A", "final", "DOC-A", "DOC-B"), q("N", "final")));

        assertThat(t).contains("| A | mention | DOC-A, DOC-B |").contains("| N | mention | (없음) |");
        assertThat(t.lines().count()).isEqualTo(4);
    }
}
