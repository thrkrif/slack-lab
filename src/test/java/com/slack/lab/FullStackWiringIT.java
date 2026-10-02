package com.slack.lab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.lab.core.model.ReactionResult;
import com.slack.lab.core.model.ReplyMetadata;
import com.slack.lab.core.model.SlackSendResult;
import com.slack.lab.core.port.ChatNotifier;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 실제 스프링 컨텍스트 전체(역할 all)를 RabbitMQ·Postgres 컨테이너에 붙이고 Redis 없는 종단 테스트(M22).
 * 서명된 이벤트가 수신 → 큐 저장 확인 → 워커 → 선점 → LLM(echo) → 발신(가짜 Slack) → 확정 → ack까지 흐르고, 즉시 반응이
 * 붙으며, 같은 이벤트의 재전송은 답글을 늘리지 않는다. Slack 호출만 가짜다.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.role=all", "llm.client=echo", "llm.model=m",
        "slack.signing-secret=testsecret", "slack.bot-token=xoxb-test", "recovery.auto-check.enabled=false",
        "rabbitmq.eventsQueue=fullstack.events"})
class FullStackWiringIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void containers(DynamicPropertyRegistry r) {
        r.add("rabbitmq.host", RABBIT::getHost);
        r.add("rabbitmq.port", RABBIT::getAmqpPort);
        r.add("postgres.url", PG::getJdbcUrl);
        r.add("postgres.username", PG::getUsername);
        r.add("postgres.password", PG::getPassword);
    }

    @MockitoBean
    ChatNotifier chat;

    @Autowired
    TestRestTemplate rest;

    @Autowired
    java.util.List<com.slack.lab.core.port.BacklogProbe> probes;

    JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),
                PG.getUsername(), PG.getPassword()));
        when(chat.postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("2.2"));
        when(chat.addReaction(anyString(), anyString(), anyString())).thenReturn(new ReactionResult(true, "added"));
    }

    static String payload(String eventId, String extraEventFields) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId + "\",\"authorizations\":[{\"user_id\":\"UBOT\"}],"
                + "\"event\":{\"type\":\"app_mention\",\"channel\":\"C1\",\"user\":\"U1\",\"text\":\"<@UBOT> 안녕\","
                + "\"ts\":\"100.1\"" + extraEventFields + "}}";
    }

    static String sign(String body, String timestamp) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("testsecret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(("v0:" + timestamp + ":" + body).getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder("v0=");
        for (byte b : h) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    int post(String body, String signature) throws Exception {
        String ts = String.valueOf(System.currentTimeMillis() / 1000);
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.set("X-Slack-Request-Timestamp", ts);
        h.set("X-Slack-Signature", signature != null ? signature : sign(body, ts));
        return rest.postForEntity("/slack/events", new HttpEntity<>(body, h), String.class).getStatusCode().value();
    }

    String stateOf(String id) {
        return jdbc.queryForList("SELECT state FROM processing_state WHERE event_id = ?", String.class, id).stream()
                .findFirst().orElse(null);
    }

    @Test
    void 적체_스냅샷은_큐와_저장소의_수치를_모두_내놓는다() {
        java.util.Map<String, Long> all = new java.util.LinkedHashMap<>();
        probes.forEach(p -> all.putAll(p.snapshot()));

        assertThat(all.keySet()).containsExactlyInAnyOrder("queue_ready", "defer", "dead", "retry", "dlq", "recovery");
    }

    @Test
    void 헬스는_RabbitMQ만_본다_Redis는_쓰지_않는다() {
        var res = rest.getForEntity("/health", Map.class);

        assertThat(res.getStatusCode().value()).isEqualTo(200);
        assertThat(res.getBody()).containsEntry("status", "UP").containsEntry("rabbitmq", "UP").doesNotContainKey("redis");
    }

    @Test
    void 서명된_이벤트가_끝까지_흘러_답글_한_번_반응_한_번으로_끝나고_재전송은_답글을_늘리지_않는다() throws Exception {
        assertThat(post(payload("EvFS1", ""), null)).isEqualTo(200);

        verify(chat, timeout(15_000)).postMessage(eq("C1"), eq("100.1"), contains("echo:"), anyLong(), any(ReplyMetadata.class));
        verify(chat, timeout(15_000)).addReaction("C1", "100.1", "eyes");
        long end = System.currentTimeMillis() + 10_000;
        while (!"COMPLETED".equals(stateOf("EvFS1")) && System.currentTimeMillis() < end) {
            Thread.sleep(100);
        }
        assertThat(stateOf("EvFS1")).isEqualTo("COMPLETED");

        // Slack 재전송: 같은 event_id가 다시 와도 200이고 답글은 늘지 않는다
        assertThat(post(payload("EvFS1", ""), null)).isEqualTo(200);
        verify(chat, after(2_500).times(1)).postMessage(anyString(), anyString(), anyString(), anyLong(),
                any(ReplyMetadata.class));
    }

    @Test
    void 서명이_틀리면_401이고_봇_메시지는_큐에_넣지_않는다() throws Exception {
        assertThat(post(payload("EvFS2", ""), "v0=deadbeef")).isEqualTo(401);
        assertThat(post(payload("EvFS3", ",\"bot_id\":\"B1\""), null)).isEqualTo(200);

        verify(chat, after(1_500).times(0)).postMessage(anyString(), anyString(), anyString(), anyLong(),
                any(ReplyMetadata.class));
        assertThat(stateOf("EvFS2")).isNull();
        assertThat(stateOf("EvFS3")).isNull();
    }
}
