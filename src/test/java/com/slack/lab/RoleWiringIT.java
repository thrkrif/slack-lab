package com.slack.lab;

import com.slack.lab.adapter.slack.SlackEventController;
import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.adapter.postgres.PostgresMaintenance;
import com.slack.lab.adapter.rabbitmq.RabbitBroker;
import com.slack.lab.adapter.rabbitmq.RabbitConsumer;
import com.slack.lab.adapter.rabbitmq.RabbitEventPublisher;
import com.slack.lab.adapter.rabbitmq.RabbitReactionConsumer;
import com.slack.lab.core.port.BacklogProbe;
import com.slack.lab.core.port.EventPublisher;
import com.slack.lab.core.service.BacklogReporter;
import com.slack.lab.core.port.ProcessingStateStore;
import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.service.EventProcessor;
import com.slack.lab.core.service.RecoveryService;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 새 구성(rabbitmq + postgres)의 역할별 빈 구성(B2). 수신은 DB 없이 큐 저장만, 워커는 처리와 유지보수, 반응은 소비자만,
 * 복구는 CLI 저장소만 갖는다. 역할을 잘못 나누면 수신 서버가 DB에 묶이거나 복구 CLI가 워커를 띄우게 된다.
 */
@Testcontainers
class RoleWiringIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    private static ConfigurableApplicationContext start(String role) {
        return new SpringApplicationBuilder(SlackLabApplication.class).run("--app.role=" + role, "--server.port=0",
                "--slack.signing-secret=s", "--slack.bot-token=t", "--llm.model=m", "--llm.client=echo",
                "--rabbitmq.host=" + RABBIT.getHost(),
                "--rabbitmq.port=" + RABBIT.getAmqpPort(), "--rabbitmq.events-queue=wiring.events",
                "--postgres.url=" + PG.getJdbcUrl(), "--postgres.username=" + PG.getUsername(),
                "--postgres.password=" + PG.getPassword(), "--recovery.exit-after-run=false",
                "--recovery.auto-check.enabled=false");
    }

    @Test
    void 수신_역할은_큐_발행만_갖고_DB와_워커는_없다() {
        try (ConfigurableApplicationContext ctx = start("receiver")) {
            assertThat(ctx.getBeanNamesForType(EventPublisher.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RabbitEventPublisher.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(ProcessingStateStore.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(RabbitConsumer.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(EventProcessor.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.LlmClient.class)).isEmpty();
        }
    }

    @Test
    void 워커_역할은_처리와_상태_유지보수를_갖고_수신_발행기_컨트롤러는_없다() {
        try (ConfigurableApplicationContext ctx = start("worker")) {
            assertThat(ctx.getBeanNamesForType(BacklogReporter.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(BacklogProbe.class)).hasSize(2);
            assertThat(ctx.getBeanNamesForType(RabbitConsumer.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RabbitReactionConsumer.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(EventProcessor.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(ProcessingStateStore.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(PostgresMaintenance.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RecoveryStore.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(com.slack.lab.adapter.slack.SlackEventController.class)).isEmpty();
        }
    }

    @Test
    void 반응_역할은_반응_소비자만_갖는다() {
        try (ConfigurableApplicationContext ctx = start("reactor")) {
            assertThat(ctx.getBeanNamesForType(RabbitReactionConsumer.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(BacklogReporter.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(RabbitConsumer.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(DataSource.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(EventProcessor.class)).isEmpty();
        }
    }

    @Test
    void 복구_역할은_복구_저장소와_명령만_갖고_소비자는_없다() {
        try (ConfigurableApplicationContext ctx = start("recovery")) {
            assertThat(ctx.getBeanNamesForType(RecoveryStore.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RecoveryService.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(BacklogReporter.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.LlmClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(DataSource.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RabbitConsumer.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(RabbitBroker.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(PostgresMaintenance.class)).isEmpty();
        }
    }
}
