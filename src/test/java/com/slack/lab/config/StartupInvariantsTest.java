package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.context.ConfigurationPropertiesAutoConfiguration;
import org.springframework.boot.autoconfigure.validation.ValidationAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

class StartupInvariantsTest {

    @Configuration
    @EnableConfigurationProperties({SlackProperties.class, LlmProperties.class, ProcessingProperties.class,
            StateProperties.class, QueueProperties.class, RetryProperties.class})
    @Import(StartupInvariants.class)
    static class Config {
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ConfigurationPropertiesAutoConfiguration.class,
                    ValidationAutoConfiguration.class))
            .withUserConfiguration(Config.class)
            .withPropertyValues("slack.signing-secret=s", "slack.bot-token=t", "llm.base-url=http://localhost:11434/v1",
                    "llm.model=m");

    @Test
    void 기본값은_모든_불변식을_만족해_기동에_성공한다() {
        runner.run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 기한_합이_총_기한을_1ms라도_넘으면_기동에_실패한다() {
        runner.withPropertyValues("llm.deadline-ms=50001").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("llm.deadline-ms"));
        runner.withPropertyValues("llm.deadline-ms=50000", "slack.send-deadline-ms=10000")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 갱신_주기가_임대의_3분의_1을_넘으면_기동에_실패한다() {
        runner.withPropertyValues("state.renew-ms=10001").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("state.renew-ms"));
    }

    @Test
    void 회수_최소_유휴가_총_기한과_임대의_합보다_작으면_기동에_실패한다() {
        runner.withPropertyValues("queue.claim-min-idle-ms=89999").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("queue.claim-min-idle-ms"));
        runner.withPropertyValues("queue.claim-min-idle-ms=90000").run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    void 재시도_대기_항목이_재시도_횟수보다_적으면_기동에_실패한다() {
        runner.withPropertyValues("retry.max-retries=4").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("retry.backoff-ms"));
    }

    @Test
    void 합이_long_범위를_넘쳐도_위반으로_잡는다() {
        runner.withPropertyValues("llm.deadline-ms=" + Long.MAX_VALUE).run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("llm.deadline-ms"));
    }

    @Test
    void 재시도_대기에_0_이하가_있으면_기동에_실패한다() {
        runner.withPropertyValues("retry.backoff-ms=5000,-1,120000").run(ctx ->
                assertThat(ctx.getStartupFailure()).hasStackTraceContaining("backoffMs"));
    }
}
