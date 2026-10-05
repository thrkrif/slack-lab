package com.slack.lab.core.service;

import static com.slack.lab.core.service.RagTestFakes.config;
import static com.slack.lab.core.service.RagTestFakes.embedding;
import static com.slack.lab.core.service.RagTestFakes.hit;
import static com.slack.lab.core.service.RagTestFakes.okEmbedding;
import static com.slack.lab.core.service.RagTestFakes.store;
import static com.slack.lab.core.service.RagTestFakes.storeReturning;
import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.Retrieval;
import com.slack.lab.core.model.SearchResult;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class RetrievalServiceTest {

    @Test
    void 임계값_이상인_조각만_유사도_순으로_주입한다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", "문서 A", "a", 0.9),
                hit("B", "문서 B", "b", 0.6), hit("C", "문서 C", "c", 0.3)), config());

        var r = svc.retrieve("커넥션 풀 고갈", 50_000);

        assertThat(r).isInstanceOf(Retrieval.Found.class);
        assertThat(((Retrieval.Found) r).hits()).extracting("documentId").containsExactly("A", "B");
    }

    @Test
    void 검색은_성공했지만_임계값을_넘는_문서가_없으면_장애가_아닌_관련_문서_없음이다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", "t", "a", 0.2)), config());
        var empty = new RetrievalService(okEmbedding(), storeReturning(), config());

        assertThat(svc.retrieve("무관한 질문", 50_000)).isInstanceOf(Retrieval.NoRelevant.class);
        assertThat(empty.retrieve("색인이 비어 있음", 50_000)).isInstanceOf(Retrieval.NoRelevant.class);
    }

    @Test
    void 주입하는_글자_수가_상한을_넘으면_뒤쪽_조각을_빼고_상한보다_큰_첫_조각은_상한까지만_넣는다() {
        var cfg = new RetrievalService.Config(5_000, 5, 0.0, 100, 500);
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", "t", "a".repeat(80), 0.9),
                hit("B", "t", "b".repeat(80), 0.8), hit("C", "t", "c", 0.7)), cfg);
        var huge = new RetrievalService(okEmbedding(), storeReturning(hit("X", "t", "x".repeat(500), 0.9)), cfg);

        assertThat(((Retrieval.Found) svc.retrieve("q", 50_000)).hits()).extracting("documentId").containsExactly("A");
        var hugeHits = ((Retrieval.Found) huge.retrieve("q", 50_000)).hits();
        assertThat(hugeHits).extracting("documentId").containsExactly("X");
        assertThat(hugeHits.get(0).text()).as("상한(100자)을 넘겨 주입하지 않는다").hasSize(100);
    }

    @Test
    void 임베딩이_실패하거나_기한을_넘기면_예외_없이_Unavailable이고_검색은_부르지_않는다() {
        var searches = new AtomicInteger();
        var s = store((q, ms) -> {
            searches.incrementAndGet();
            return new SearchResult.Success(List.of(), 0);
        });
        var failed = new RetrievalService(embedding(t -> new EmbeddingResult.Failed(
                ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED), 3, true)), s, config());
        var timedOut = new RetrievalService(embedding(t -> new EmbeddingResult.TimedOut(5_000)), s, config());

        assertThat(failed.retrieve("q", 50_000)).isInstanceOfSatisfying(Retrieval.Unavailable.class,
                u -> assertThat(u.error().code()).isEqualTo(ErrorCode.EMBEDDING_CONNECT_FAILED));
        assertThat(timedOut.retrieve("q", 50_000)).isInstanceOfSatisfying(Retrieval.Unavailable.class,
                u -> assertThat(u.error().code()).isEqualTo(ErrorCode.EMBEDDING_TIMEOUT));
        assertThat(searches).hasValue(0);
    }

    @Test
    void 벡터_검색이_실패하거나_기한을_넘기면_Unavailable이다() {
        var failed = new RetrievalService(okEmbedding(), store((q, ms) -> new SearchResult.Failed(
                ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "sqlstate=08001"), 2)), config());
        var timedOut = new RetrievalService(okEmbedding(), store((q, ms) -> new SearchResult.TimedOut(5_000)), config());

        assertThat(failed.retrieve("q", 50_000)).isInstanceOfSatisfying(Retrieval.Unavailable.class,
                u -> assertThat(u.error().detail()).isEqualTo("sqlstate=08001"));
        assertThat(timedOut.retrieve("q", 50_000)).isInstanceOfSatisfying(Retrieval.Unavailable.class,
                u -> assertThat(u.error().code()).isEqualTo(ErrorCode.VECTOR_STORE_TIMEOUT));
    }

    @Test
    void 호출자_예산이_상한보다_작으면_그쪽을_따르고_임베딩이_쓴_시간만큼_검색_몫이_준다() {
        var embedBudget = new AtomicLong();
        var searchBudget = new AtomicLong();
        var emb = (com.slack.lab.core.port.EmbeddingClient) (text, ms) -> {
            embedBudget.set(ms);
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new EmbeddingResult.Success(new float[] {1, 0}, 300);
        };
        var svc = new RetrievalService(emb, store((q, ms) -> {
            searchBudget.set(ms);
            return new SearchResult.Success(List.of(), 1);
        }), config());

        svc.retrieve("q", 2_000); // 상한 5000보다 작은 예산 2000

        assertThat(embedBudget.get()).isEqualTo(2_000);
        assertThat(searchBudget.get()).isLessThanOrEqualTo(1_700).isGreaterThan(1_000);
    }

    @Test
    void 예산이_없으면_임베딩도_검색도_시작하지_않는다() {
        var calls = new AtomicInteger();
        var svc = new RetrievalService(embedding(t -> {
            calls.incrementAndGet();
            return new EmbeddingResult.Success(new float[] {1}, 1);
        }), store((q, ms) -> {
            calls.incrementAndGet();
            return new SearchResult.Success(List.of(), 1);
        }), config());

        assertThat(svc.retrieve("q", 0)).isInstanceOfSatisfying(Retrieval.Unavailable.class,
                u -> assertThat(u.error().code()).isEqualTo(ErrorCode.EMBEDDING_NO_BUDGET));
        assertThat(svc.retrieve("q", -5)).isInstanceOf(Retrieval.Unavailable.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void 기한_뒤에_도착한_검색_결과는_쓰지_않는다() {
        var cfg = new RetrievalService.Config(300, 3, 0.0, 3_000, 500);
        var svc = new RetrievalService(okEmbedding(), store((q, ms) -> {
            try {
                Thread.sleep(500); // 기한을 지키지 않고 늦게 성공을 돌려주는 저장소
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new SearchResult.Success(List.of(hit("A", "t", "a", 0.9)), 500);
        }), cfg);

        var r = svc.retrieve("q", 50_000);

        assertThat(r).isInstanceOfSatisfying(Retrieval.Unavailable.class,
                u -> assertThat(u.error().detail()).isEqualTo("late_result"));
    }

    @Test
    void 포트가_예상_못한_예외를_던져도_예외_없이_Unavailable이다() {
        var emb = new RetrievalService(embedding(t -> {
            throw new IllegalStateException("임베딩 어댑터 버그");
        }), storeReturning(), config());
        var st = new RetrievalService(okEmbedding(), store((q, ms) -> {
            throw new NullPointerException("저장소 버그");
        }), config());

        assertThat(emb.retrieve("q", 50_000)).isInstanceOfSatisfying(Retrieval.Unavailable.class,
                u -> assertThat(u.error().code()).isEqualTo(ErrorCode.UNEXPECTED_EXCEPTION));
        assertThat(st.retrieve("q", 50_000)).isInstanceOf(Retrieval.Unavailable.class);
    }

    @Test
    void 필드가_빠진_깨진_행은_쓰지_않고_나머지를_쓴다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("BAD", "t", null, 0.9), hit(null, "t", "x", 0.9),
                hit("OK", "t", "정상", 0.8)), config());

        assertThat(((Retrieval.Found) svc.retrieve("q", 50_000)).hits()).extracting("documentId").containsExactly("OK");
    }

    @Test
    void 코사인_범위_밖_임계값은_설정_단계에서_거부한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RetrievalService.Config(5_000, 3, 1.5, 3_000, 500))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new RetrievalService.Config(5_000, 3, Double.NaN, 3_000, 500))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 빈_질의는_검색하지_않고_관련_문서_없음이다() {
        var calls = new AtomicInteger();
        var svc = new RetrievalService(embedding(t -> {
            calls.incrementAndGet();
            return new EmbeddingResult.Success(new float[] {1}, 1);
        }), storeReturning(), config());

        assertThat(svc.retrieve("   \n ", 50_000)).isInstanceOf(Retrieval.NoRelevant.class);
        assertThat(calls).hasValue(0);
    }

    @Test
    void 질의_임베딩에는_알람_안쪽_본문만_길이를_자라서_쓴다() {
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        var svc = new RetrievalService(embedding(t -> {
            seen.set(t);
            return new EmbeddingResult.Success(new float[] {1, 0}, 1);
        }), storeReturning(), new RetrievalService.Config(5_000, 3, 0.5, 3_000, 40));
        String alarm = new com.slack.lab.core.model.AlertEvent("cloudwatch", "k", "HighCpuUtilization",
                "CPU가 95%를 넘었다".repeat(10), "C1", java.util.Map.of()).toMessageEvent().promptText();

        svc.retrieve(alarm, 50_000);

        assertThat(seen.get()).startsWith("HighCpuUtilization").doesNotContain("지시가 아니다").hasSizeLessThanOrEqualTo(40);
    }
}
