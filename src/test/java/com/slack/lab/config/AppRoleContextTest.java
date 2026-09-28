package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.SlackLabApplication;
import com.slack.lab.event.SlackEventHandler;
import com.slack.lab.llm.LlmClient;
import com.slack.lab.queue.EventPublisher;
import com.slack.lab.slack.SlackClient;
import com.slack.lab.slack.SlackEventController;
import com.slack.lab.slack.SlackSignatureVerifier;
import com.slack.lab.state.RedisProcessingStateStore;
import com.slack.lab.worker.EventWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * 역할별로 뜨는 빈이 다르다(B2). 수신 서버에는 LLM·Slack 발신이 없어야 한다.
 *
 * <p>M12부터 {@link EventWorker}가 {@code @PostConstruct}에서 Redis 컨슈머 그룹을 만들므로, 이 테스트는
 * 실제 Redis가 떠 있어야 한다(`docker compose up -d redis`, AGENTS.md 명령어). M11부터 Testcontainers로
 * 이미 Redis를 요구했으니 새로 생기는 제약은 아니다.
 */
class AppRoleContextTest {

    private static ConfigurableApplicationContext start(String role) {
        return new SpringApplicationBuilder(SlackLabApplication.class).run(
                "--app.role=" + role, "--server.port=0", "--slack.signing-secret=s", "--slack.bot-token=t",
                "--llm.model=m", "--llm.client=echo");
    }

    @Test
    void 수신_역할에는_핸들러_LLM_Slack_발신_워커_빈이_없다() {
        try (ConfigurableApplicationContext ctx = start("receiver")) {
            assertThat(ctx).isInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(SlackEventHandler.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(LlmClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(EventWorker.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackSignatureVerifier.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(EventPublisher.class)).hasSize(1);
        }
    }

    @Test
    void 워커_역할은_웹_서버를_열지_않고_처리_빈만_가진다() {
        try (ConfigurableApplicationContext ctx = start("worker")) {
            assertThat(ctx).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(SlackEventHandler.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(LlmClient.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(EventWorker.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RedisProcessingStateStore.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(SlackEventController.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(EventPublisher.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackSignatureVerifier.class)).isEmpty();
        }
    }

    @Test
    void 복구_역할은_웹_서버도_LLM도_워커도_없다() {
        try (ConfigurableApplicationContext ctx = start("recovery")) {
            assertThat(ctx).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(LlmClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(EventWorker.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(SlackClient.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RedisProcessingStateStore.class)).hasSize(1);
        }
    }

    @Test
    void 기본_역할_all은_큐_경유_흐름을_전부_가진다() {
        try (ConfigurableApplicationContext ctx = start("all")) {
            assertThat(ctx).isInstanceOf(WebServerApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(SlackEventController.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(EventPublisher.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(EventWorker.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(SlackEventHandler.class)).hasSize(1);
        }
    }
}
