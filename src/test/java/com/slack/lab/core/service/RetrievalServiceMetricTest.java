package com.slack.lab.core.service;

import static com.slack.lab.core.service.RagTestFakes.config;
import static com.slack.lab.core.service.RagTestFakes.embedding;
import static com.slack.lab.core.service.RagTestFakes.hit;
import static com.slack.lab.core.service.RagTestFakes.okEmbedding;
import static com.slack.lab.core.service.RagTestFakes.store;
import static com.slack.lab.core.service.RagTestFakes.storeReturning;
import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.Retrieval;
import com.slack.lab.core.model.SearchResult;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

/** 5단계 지표(PLAN M37): 운영 검색 호출당 {@code metric=rag_result} 줄이 정확히 1개이고 금지 정보가 없다(C3·C4). */
class RetrievalServiceMetricTest {

    private static final String QUESTION = "비밀질문-결제-카드번호-1234";
    private static final String TITLE = "문서제목-내부-런북";

    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attachAppender() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(RetrievalService.class)).addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        ((Logger) LoggerFactory.getLogger(RetrievalService.class)).detachAppender(appender);
    }

    private List<String> metricLines() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.contains("metric=rag_result")).toList();
    }

    @Test
    void 관련_문서를_찾으면_found_한_줄이다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", TITLE, "본문", 0.9)), config());

        assertThat(svc.retrieve(QUESTION, 50_000)).isInstanceOf(Retrieval.Found.class);

        assertThat(metricLines()).singleElement().asString().startsWith("metric=rag_result result=found elapsed_ms=");
    }

    @Test
    void 임계값을_못_넘으면_none_한_줄이다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", TITLE, "본문", 0.1)), config());

        assertThat(svc.retrieve(QUESTION, 50_000)).isInstanceOf(Retrieval.NoRelevant.class);

        assertThat(metricLines()).singleElement().asString().startsWith("metric=rag_result result=none elapsed_ms=");
    }

    @Test
    void 빈_질의도_실제_호출이라_none_한_줄로_센다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", TITLE, "본문", 0.9)), config());

        assertThat(svc.retrieve("   ", 50_000)).isInstanceOf(Retrieval.NoRelevant.class);

        assertThat(metricLines()).singleElement().asString().contains("result=none");
    }

    @Test
    void 예산이_없으면_unavailable과_오류_코드_이름_한_줄이다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(), config());

        assertThat(svc.retrieve(QUESTION, 0)).isInstanceOf(Retrieval.Unavailable.class);

        assertThat(metricLines()).singleElement().asString()
                .contains("result=unavailable").contains("reason=embedding_no_budget");
    }

    @Test
    void 임베딩_타임아웃_실패_벡터_저장소_실패_예상_못한_예외는_각각_unavailable_한_줄이다() {
        var timeout = new RetrievalService(embedding(t -> new EmbeddingResult.TimedOut(5_000)), storeReturning(), config());
        var failed = new RetrievalService(embedding(t -> new EmbeddingResult.Failed(
                ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED), 3, true)), storeReturning(), config());
        var storeTimeout = new RetrievalService(okEmbedding(), store((q, ms) -> new SearchResult.TimedOut(5_000)), config());
        var storeFailed = new RetrievalService(okEmbedding(), store((q, ms) -> new SearchResult.Failed(
                ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED), 3)), config());
        var thrown = new RetrievalService(embedding(t -> {
            throw new IllegalStateException("내부 예외 메시지-" + QUESTION);
        }), storeReturning(), config());

        for (var svc : List.of(timeout, failed, storeTimeout, storeFailed, thrown)) {
            assertThat(svc.retrieve(QUESTION, 50_000)).isInstanceOf(Retrieval.Unavailable.class);
        }

        assertThat(metricLines()).hasSize(5);
        assertThat(metricLines()).allSatisfy(l -> assertThat(l).contains("result=unavailable").contains("reason="));
        assertThat(metricLines()).extracting(l -> l.replaceAll(".*reason=(\\S+).*", "$1")).containsExactly(
                "embedding_timeout", "embedding_connect_failed", "vector_store_timeout", "vector_store_failed",
                "unexpected_exception");
    }

    @Test
    void 선택_단계에서_던져도_줄은_한_번_남고_예외는_그대로_전파된다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", TITLE, "본문", 0.9)), config()) {
            @Override
            public Ranking rank(String promptText, long budgetMs) {
                throw new IllegalStateException("rank 밖 예외");
            }
        };

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> svc.retrieve(QUESTION, 50_000))
                .isInstanceOf(IllegalStateException.class);

        assertThat(metricLines()).singleElement().asString()
                .contains("result=unavailable").contains("reason=unexpected_exception");
    }

    @Test
    void 평가가_쓰는_rank_직접_호출은_운영_검색이_아니라_줄을_남기지_않는다() {
        var svc = new RetrievalService(okEmbedding(), storeReturning(hit("A", TITLE, "본문", 0.9)), config());

        svc.rank(QUESTION, 50_000);

        assertThat(metricLines()).isEmpty();
    }

    @Test
    void 줄에_질문_문서_제목_예외_메시지가_들어가지_않는다() {
        var found = new RetrievalService(okEmbedding(), storeReturning(hit("A", TITLE, "본문", 0.9)), config());
        var thrown = new RetrievalService(embedding(t -> {
            throw new IllegalStateException("내부 예외 메시지-" + QUESTION);
        }), storeReturning(), config());

        found.retrieve(QUESTION, 50_000);
        thrown.retrieve(QUESTION, 50_000);

        assertThat(metricLines()).hasSize(2).allSatisfy(l -> assertThat(l)
                .doesNotContain("비밀질문").doesNotContain("카드번호").doesNotContain("문서제목").doesNotContain("내부 예외"));
    }
}
