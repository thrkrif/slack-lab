package com.slack.lab.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.queue.ReactionProperties;
import com.slack.lab.slack.SlackClient;
import com.slack.lab.slack.SlackProperties;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 반응 소비자(M15): 성공·이미 붙음·실패 모두 재시도 없이 항목을 지우고, 죽은 소비자의 항목은 회수한다. */
@Testcontainers
class ReactionConsumerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final ReactionProperties PROPS = new ReactionProperties("slack:reactions", "reactors", "eyes", 200, 0);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    HttpServer server;
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile String response = "{\"ok\":true}";
    ReactionConsumer consumer;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void reset() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        bodies.clear();
        response = "{\"ok\":true}";
        redis.execute(c -> {
            c.serverCommands().flushAll();
            return null;
        }, true);
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/reactions.add", ex -> {
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = response.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (var os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        server.start();
        var slack = new SlackClient(new SlackProperties("s", "xoxb-test",
                "http://127.0.0.1:" + server.getAddress().getPort(), 10_000), new ObjectMapper());
        consumer = new ReactionConsumer(redis, PROPS, slack);
    }

    void publishReaction(String eventId) {
        redis.opsForStream().add(MapRecord.create(PROPS.streamKey(), Map.of("event_id", eventId, "channel", "C1",
                "ts", "100.1", "received_at", String.valueOf(System.currentTimeMillis()))));
    }

    long len() {
        Long n = redis.opsForStream().size(PROPS.streamKey());
        return n == null ? 0 : n;
    }

    void awaitEmpty() throws InterruptedException {
        for (int i = 0; i < 100 && len() > 0; i++) {
            Thread.sleep(50);
        }
    }

    @Test
    void 반응_항목을_소비해_이모지를_붙이고_항목을_지운다() throws Exception {
        publishReaction("R1");
        consumer.start();
        try {
            awaitEmpty();
        } finally {
            consumer.stop();
        }

        assertThat(len()).isZero();
        assertThat(bodies).hasSize(1);
        assertThat(bodies.get(0)).contains("\"name\":\"eyes\"").contains("\"channel\":\"C1\"")
                .contains("\"timestamp\":\"100.1\"");
    }

    @Test
    void already_reacted도_성공으로_보고_항목을_지운다() throws Exception {
        response = "{\"ok\":false,\"error\":\"already_reacted\"}";
        publishReaction("R2");
        consumer.start();
        try {
            awaitEmpty();
        } finally {
            consumer.stop();
        }

        assertThat(len()).isZero();
        assertThat(bodies).hasSize(1);
    }

    @Test
    void 스코프_오류로_실패해도_재시도하지_않고_항목을_지운다() throws Exception {
        response = "{\"ok\":false,\"error\":\"missing_scope\"}";
        publishReaction("R3");
        consumer.start();
        try {
            awaitEmpty();
            Thread.sleep(400); // 재시도가 있었다면 그 사이 더 호출됐을 것이다
        } finally {
            consumer.stop();
        }

        assertThat(len()).isZero();
        assertThat(bodies).hasSize(1);
    }

    @Test
    void 죽은_소비자의_항목은_짧은_min_idle_뒤_회수해_처리한다() throws Exception {
        redis.opsForStream().createGroup(PROPS.streamKey(), ReadOffset.from("0"), PROPS.group());
        publishReaction("R4");
        // 다른 소비자가 읽기만 하고 죽었다(ACK 없음)
        redis.opsForStream().read(Consumer.from(PROPS.group(), "dead"), StreamReadOptions.empty().count(10),
                StreamOffset.create(PROPS.streamKey(), ReadOffset.lastConsumed()));
        assertThat(redis.opsForStream().pending(PROPS.streamKey(), PROPS.group()).getTotalPendingMessages())
                .isEqualTo(1);
        Thread.sleep(PROPS.reclaimMinIdleMs() + 100);

        consumer.reclaimStale();

        assertThat(len()).isZero();
        assertThat(bodies).hasSize(1);
    }

    @Test
    void 처리_스트림과_무관하게_반응_항목이_남는다() {
        // 반응 스트림은 처리 그룹의 XDEL과 독립이다 — 이벤트 스트림을 비워도 반응 항목은 그대로다.
        publishReaction("R5");
        redis.opsForStream().add(MapRecord.create("slack:events", Map.of("event_id", "R5")));
        redis.delete("slack:events");

        assertThat(len()).isEqualTo(1);
    }
}
