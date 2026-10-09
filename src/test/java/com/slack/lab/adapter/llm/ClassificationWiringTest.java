package com.slack.lab.adapter.llm;

import com.slack.lab.config.ClassificationProperties;
import com.slack.lab.config.ClassificationStartupCheck;
import com.slack.lab.config.LlmProperties;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.port.RequestClassifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

/**
 * 분류 설정 → 빈 배선. 켜지 않으면 분류기·기동 검사 빈이 없어 핸들러가 3단계 흐름 그대로 가고(꺼도 동작), 켜면 둘 다 생기며,
 * 잘못된 조합은 컨텍스트가 뜨지 않는다(조용한 오동작 금지).
 */
class ClassificationWiringTest {

    private static final String[] BASE = {"llm.base-url=http://localhost:1/v1", "llm.model=qwen2.5:3b", "llm.client=ollama",
            "app.role=evaluator"};

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(Cfg.class).withPropertyValues(BASE);
    }

    @Test
    void 꺼_두면_분류기와_기동_검사_빈이_없다() {
        runner().run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).doesNotHaveBean(RequestClassifier.class);
            assertThat(ctx).doesNotHaveBean(ClassificationStartupCheck.class);
        });
    }

    @Test
    void 켜면_분류기와_기동_검사_빈이_생긴다() {
        // 확인 호출은 닫힌 포트라 연결 실패(일시 오류)로 경고만 하고 기동한다.
        runner().withPropertyValues("classification.enabled=true", "classification.model=qwen2.5:3b",
                "classification.timeout-ms=500").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx).hasSingleBean(RequestClassifier.class);
            assertThat(ctx).hasSingleBean(ClassificationStartupCheck.class);
        });
    }

    @Test
    void 켰는데_모델_ID가_없으면_기동을_거부한다() {
        runner().withPropertyValues("classification.enabled=true").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("classification.model");
        });
    }

    @Test
    void Echo_클라이언트와_함께_켜면_기동을_거부한다() {
        runner().withPropertyValues("llm.client=echo", "classification.enabled=true", "classification.model=m")
                .run(ctx -> {
                    assertThat(ctx).hasFailed();
                    assertThat(ctx.getStartupFailure()).hasStackTraceContaining("ECHO");
                });
    }

    @Test
    void 수신_역할에는_분류기가_없다() {
        runner().withPropertyValues("app.role=receiver", "classification.enabled=true", "classification.model=m")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(RequestClassifier.class));
    }

    @EnableConfigurationProperties({ClassificationProperties.class, LlmProperties.class})
    @Import({ClassificationConfig.class, ClassificationStartupCheck.class})
    static class Cfg {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }
    }
}
