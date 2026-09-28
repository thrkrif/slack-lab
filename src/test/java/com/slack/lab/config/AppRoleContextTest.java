package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.SlackLabApplication;
import com.slack.lab.event.EventDeduplicator;
import com.slack.lab.event.SlackEventHandler;
import com.slack.lab.llm.LlmClient;
import com.slack.lab.slack.SlackClient;
import com.slack.lab.slack.SlackEventController;
import com.slack.lab.slack.SlackSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/** 역할별로 뜨는 빈이 다르다(B2). 수신 서버에는 LLM·Slack 발신이 없어야 한다. */
class AppRoleContextTest {

    private static ConfigurableApplicationContext start(String role) {
        return new SpringApplicationBuilder(SlackLabApplication.class).run(
                "--app.role=" + role, "--server.port=0", "--slack.signing-secret=s", "--slack.bot-token=t",
                "--llm.model=m", "--llm.client=echo");
    }

    @Test
    void 수신_역할에는_핸들러_LLM_Slack_발신_빈이_없다() {
        try (ConfigurableApplicationContext ctx = start("receiver")) {
            assertThat(ctx).isInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(SlackEventHandler.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(LlmClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackSignatureVerifier.class)).hasSize(1);
        }
    }

    @Test
    void 워커_역할은_웹_서버를_열지_않고_처리_빈만_가진다() {
        try (ConfigurableApplicationContext ctx = start("worker")) {
            assertThat(ctx).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(SlackEventHandler.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(LlmClient.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(SlackEventController.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackSignatureVerifier.class)).isEmpty();
        }
    }

    @Test
    void 복구_역할은_웹_서버도_LLM도_없다() {
        try (ConfigurableApplicationContext ctx = start("recovery")) {
            assertThat(ctx).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(LlmClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackClient.class)).hasSize(1);
        }
    }

    @Test
    void 기본_역할_all은_1단계_흐름을_그대로_유지한다() {
        try (ConfigurableApplicationContext ctx = start("all")) {
            assertThat(ctx).isInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(SlackEventController.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(EventDeduplicator.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(SlackEventHandler.class)).hasSize(1);
        }
    }
}
