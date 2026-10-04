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
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(com.slack.lab.TestImages.POSTGRES);

    private static ConfigurableApplicationContext start(String role, String... extra) {
        java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--app.role=" + role, "--server.port=0",
                "--slack.signing-secret=s", "--slack.bot-token=t", "--llm.model=m", "--llm.client=echo",
                "--rabbitmq.host=" + RABBIT.getHost(),
                "--rabbitmq.port=" + RABBIT.getAmqpPort(), "--rabbitmq.events-queue=wiring.events",
                "--postgres.url=" + PG.getJdbcUrl(), "--postgres.username=" + PG.getUsername(),
                "--postgres.password=" + PG.getPassword(), "--recovery.exit-after-run=false",
                "--recovery.auto-check.enabled=false"));
        // 같은 키가 두 번 나오면 값이 "a,b"로 합쳐져 바인딩이 깨진다 — 나중 값이 이기도록 앞의 것을 지운다.
        for (String e : extra) {
            String key = e.substring(0, e.indexOf('=') + 1);
            args.removeIf(a -> a.startsWith(key));
            args.add(e);
        }
        return new SpringApplicationBuilder(SlackLabApplication.class).run(args.toArray(String[]::new));
    }

    // 임베딩 서버 없이 배선만 본다(모델·차원 확인은 별도 테스트).
    private static final String[] RAG_ON = {"--rag.enabled=true", "--rag.embedding-model=bge-m3",
            "--rag.embedding-dimension=1024", "--rag.verify-on-startup=false"};

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

    @Test
    void RAG를_끄면_임베딩과_벡터_저장소_빈이_없고_워커는_그대로_뜬다() {
        try (ConfigurableApplicationContext ctx = start("worker")) {
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.VectorStore.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.EmbeddingClient.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(EventProcessor.class)).hasSize(1);
        }
    }

    @Test
    void RAG를_켜면_워커가_임베딩과_벡터_저장소를_갖는다() {
        try (ConfigurableApplicationContext ctx = start("worker", RAG_ON)) {
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.VectorStore.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.EmbeddingClient.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(EventProcessor.class)).hasSize(1);
        }
    }

    @Test
    void 색인_역할은_웹_포트와_큐_소비자_없이_임베딩과_벡터_저장소만_갖는다() {
        try (ConfigurableApplicationContext ctx = start("indexer", RAG_ON)) {
            assertThat(ctx).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(DataSource.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.VectorStore.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.EmbeddingClient.class)).hasSize(1);
            assertThat(ctx.getBeanNamesForType(RabbitConsumer.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(RabbitBroker.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(EventProcessor.class)).isEmpty();
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.LlmClient.class)).isEmpty();
        }
    }

    @Test
    void 외부_LLM을_허용하지_않고_RAG를_켜면_워커_기동이_거부된다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> start("worker", RAG_ON[0], RAG_ON[1], RAG_ON[2],
                RAG_ON[3], "--llm.client=ollama", "--llm.base-url=https://api.example.com/v1",
                "--llm.verify-model-on-startup=false")).hasStackTraceContaining("rag.allow-external-llm");
    }

    @Test
    void 색인이_다른_모델로_만들어졌으면_워커_기동이_거부되고_색인_역할은_뜬다() {
        com.zaxxer.hikari.HikariConfig c = new com.zaxxer.hikari.HikariConfig();
        c.setJdbcUrl(PG.getJdbcUrl());
        c.setUsername(PG.getUsername());
        c.setPassword(PG.getPassword());
        try (com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource(c);
                var store = new com.slack.lab.adapter.postgres.PostgresVectorStore(ds)) {
            com.slack.lab.adapter.postgres.PostgresMigrations.migrate(ds);
            store.initMeta(new com.slack.lab.core.model.IndexMeta("nomic-embed-text", 768));
            try {
                org.assertj.core.api.Assertions.assertThatThrownBy(() -> start("worker", RAG_ON))
                        .hasStackTraceContaining("전체 재색인").hasStackTraceContaining("nomic-embed-text");
                // 거부하기 전에 큐를 소비하지 않았다: 이벤트 큐에 소비자가 한 명도 붙지 않았다
                var cf = new com.rabbitmq.client.ConnectionFactory();
                cf.setHost(RABBIT.getHost());
                cf.setPort(RABBIT.getAmqpPort());
                try (var conn = cf.newConnection(); var ch = conn.createChannel()) {
                    assertThat(ch.queueDeclarePassive("wiring.events").getConsumerCount()).isZero();
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                // 색인 역할은 이 불일치를 풀기 위한 경로라 검사하지 않는다
                try (ConfigurableApplicationContext ctx = start("indexer", RAG_ON)) {
                    assertThat(ctx.getBeanNamesForType(com.slack.lab.core.port.VectorStore.class)).hasSize(1);
                }
            } finally {
                var jdbc = new org.springframework.jdbc.core.JdbcTemplate(ds);
                jdbc.update("UPDATE rag_index_state SET live_generation = NULL, pending_generation = NULL");
                jdbc.update("DELETE FROM rag_generation");
            }
        }
    }
}
