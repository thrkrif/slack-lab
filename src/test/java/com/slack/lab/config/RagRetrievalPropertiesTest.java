package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** 기본값은 M30 실측으로 정한 값이다. 바꾸면 EXPERIMENT-LOG §31의 근거와 함께 바꾼다. */
class RagRetrievalPropertiesTest {

    @EnableConfigurationProperties({RagRetrievalProperties.class, RagProperties.class})
    static class Cfg {}

    @Test
    void 기본값은_측정으로_정한_값이다() {
        new ApplicationContextRunner().withUserConfiguration(Cfg.class).run(ctx -> {
            var r = ctx.getBean(RagRetrievalProperties.class);
            assertThat(r.topK()).isEqualTo(3);
            assertThat(r.minScore()).isEqualTo(0.54);
            assertThat(r.maxContextChars()).isEqualTo(1500);
            assertThat(r.maxQueryChars()).isEqualTo(500);
            var rag = ctx.getBean(RagProperties.class);
            assertThat(rag.enabled()).as("RAG는 기본 꺼짐").isFalse();
            assertThat(rag.searchDeadlineMs()).isEqualTo(5_000);
        });
    }

    @Test
    void 환경변수로_조정할_수_있다() {
        new ApplicationContextRunner().withUserConfiguration(Cfg.class)
                .withPropertyValues("rag.retrieval.min-score=0.6", "rag.retrieval.max-context-chars=900")
                .run(ctx -> {
                    assertThat(ctx.getBean(RagRetrievalProperties.class).minScore()).isEqualTo(0.6);
                    assertThat(ctx.getBean(RagRetrievalProperties.class).maxContextChars()).isEqualTo(900);
                });
    }
}
