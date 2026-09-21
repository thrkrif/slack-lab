package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.llm.LlmProperties;
import com.slack.lab.slack.SlackProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

class RequiredPropertiesTest {

    @Configuration
    @EnableConfigurationProperties({SlackProperties.class, LlmProperties.class,
            ProcessingProperties.class, ExperimentProperties.class})
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class);

    private static final String[] REQUIRED = {
            "slack.signing-secret=s", "slack.bot-token=t",
            "llm.base-url=http://localhost:11434/v1", "llm.model=m"};

    @Test
    void 필수_값이_있으면_기본값과_함께_바인딩된다() {
        runner.withPropertyValues(REQUIRED).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(SlackProperties.class).baseUrl()).isEqualTo("https://slack.com/api");
            assertThat(ctx.getBean(SlackProperties.class).sendDeadlineMs()).isEqualTo(10000);
            LlmProperties llm = ctx.getBean(LlmProperties.class);
            assertThat(llm.client()).isEqualTo(LlmProperties.Client.OLLAMA);
            assertThat(llm.deadlineMs()).isEqualTo(50000);
            assertThat(llm.connectTimeoutMs()).isEqualTo(3000);
            assertThat(llm.verifyModelOnStartup()).isTrue();
            assertThat(ctx.getBean(ProcessingProperties.class).totalDeadlineMs()).isEqualTo(60000);
            assertThat(ctx.getBean(ExperimentProperties.class).slowModeMs()).isZero();
            assertThat(ctx.getBean(ExperimentProperties.class).dedupEnabled()).isTrue();
        });
    }

    @Test
    void 서명_시크릿이_비어_있으면_기동에_실패한다() {
        runner.withPropertyValues("slack.signing-secret=", "slack.bot-token=t",
                "llm.base-url=http://localhost:11434/v1", "llm.model=m").run(ctx -> {
            assertThat(ctx).hasFailed();
            assertThat(ctx.getStartupFailure()).hasStackTraceContaining("signingSecret");
        });
    }

    @Test
    void 봇_토큰과_모델_ID가_비어_있어도_기동에_실패한다() {
        runner.withPropertyValues("slack.signing-secret=s", "slack.bot-token=",
                "llm.base-url=http://localhost:11434/v1", "llm.model=m").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("botToken"));
        runner.withPropertyValues("slack.signing-secret=s", "slack.bot-token=t",
                "llm.base-url=http://localhost:11434/v1", "llm.model=").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("model"));
    }
}
