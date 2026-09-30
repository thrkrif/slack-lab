package com.slack.lab.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slack.lab.event.SlackMessageEvent;
import io.lettuce.core.output.CommandOutput;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 성공 경로(`Enqueued`)는 실제 Redis(WAITAOF까지)로 검증하고, `Failed`/`Unconfirmed`는 저장소 오류·확인
 * 실패를 실제 Redis로 재현하기 어려워 {@code StringRedisTemplate}을 목으로 대체한다(PLAN 2단계 M12).
 */
@Testcontainers
class EventPublisherTest {

    // WAITAOF numlocal>=1을 확인하려면 컨테이너도 운영과 같은 AOF 설정이어야 한다(infra/redis.conf).
    // appendfsync가 기본값(everysec)이면 background fsync가 최대 1초 지연될 수 있어 짧은 enqueue-timeout-ms
    // 안에 numlocal=1을 못 받을 수 있다(실측) — always로 맞춘다.
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--appendonly", "yes", "--appendfsync", "always");

    static final QueueProperties PROPS = new QueueProperties("slack:events", "workers", 500, 100000);
    static final ReactionProperties REACTION = new ReactionProperties("slack:reactions", "reactors", "eyes", 10000, 0);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

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
    void reset() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    static SlackMessageEvent event(String eventId) {
        return new SlackMessageEvent(eventId, "C1", "U1", "안녕", "100.1", "99.9", null, null, null);
    }

    // --- 성공 경로(실제 Redis)

    @Test
    void 발행에_성공하면_Enqueued를_돌려주고_필드가_정확히_저장된다() {
        EventPublisher publisher = new EventPublisher(redis, PROPS, REACTION);

        PublishResult result = publisher.publish(event("EP1"), 1_700_000_000_000L, "0");

        assertThat(result).isInstanceOf(PublishResult.Enqueued.class);
        Map<Object, Object> fields = lastEntryFields(((PublishResult.Enqueued) result).streamId());
        assertThat(fields)
                .containsEntry("schema_version", "1")
                .containsEntry("event_id", "EP1")
                .containsEntry("gen", "0")
                .containsEntry("received_at", "1700000000000")
                .containsEntry("channel", "C1")
                .containsEntry("ts", "100.1")
                .containsEntry("thread_ts", "99.9")
                .containsEntry("user", "U1")
                .containsEntry("text", "안녕")
                .containsEntry("retry_num", "0");
    }

    @Test
    void null_필드는_빈_문자열로_저장된다() {
        EventPublisher publisher = new EventPublisher(redis, PROPS, REACTION);
        SlackMessageEvent event = new SlackMessageEvent("EP2", "C1", null, null, "100.1", null, null, null, null);

        PublishResult result = publisher.publish(event, 1L, null);

        assertThat(result).isInstanceOf(PublishResult.Enqueued.class);
        Map<Object, Object> fields = lastEntryFields(((PublishResult.Enqueued) result).streamId());
        assertThat(fields)
                .containsEntry("thread_ts", "")
                .containsEntry("user", "")
                .containsEntry("text", "")
                .containsEntry("retry_num", "");
    }

    @Test
    void 발행하면_반응_스트림에도_본문_없는_최소_항목이_함께_들어간다() {
        EventPublisher publisher = new EventPublisher(redis, PROPS, REACTION);

        publisher.publish(event("EPR"), 1_700_000_000_000L, "0");

        List<MapRecord<String, Object, Object>> rx = redis.opsForStream().range(REACTION.streamKey(), Range.unbounded());
        assertThat(rx).hasSize(1);
        assertThat(rx.get(0).getValue()).containsOnlyKeys("event_id", "channel", "ts", "received_at")
                .containsEntry("event_id", "EPR").containsEntry("channel", "C1").containsEntry("ts", "100.1")
                .containsEntry("received_at", "1700000000000");
        assertThat(redis.opsForStream().size(PROPS.streamKey())).isEqualTo(1);
    }

    private Map<Object, Object> lastEntryFields(String streamId) {
        List<MapRecord<String, Object, Object>> range = redis.opsForStream()
                .range(PROPS.streamKey(), Range.just(streamId));
        assertThat(range).hasSize(1);
        return range.get(0).getValue();
    }

    // --- 실패·불명 경로(목)

    @Test
    @SuppressWarnings("unchecked")
    void XADD가_실패하면_Failed를_돌려준다() {
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        when(mockRedis.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RedisConnectionFailureException("연결 거부"));

        PublishResult result = new EventPublisher(mockRedis, PROPS, REACTION).publish(event("EP3"), 1L, "0");

        assertThat(result).isInstanceOf(PublishResult.Failed.class);
    }

    /**
     * {@code confirmDurable()}는 프록시가 아닌 원본 {@link LettuceConnection}을 커넥션 팩토리에서 직접 꺼내
     * `WAITAOF`를 보낸다(실측: 기본 `RedisCallback` 경로는 정수 배열 응답을 디코딩하지 못해 예외가 난다).
     * 그래서 목도 같은 경로 — {@code StringRedisTemplate.getConnectionFactory().getConnection()} — 를 흉내낸다.
     */
    @SuppressWarnings("unchecked")
    private static StringRedisTemplate mockRedisForWaitaof(LettuceConnection connection) {
        StringRedisTemplate mockRedis = mock(StringRedisTemplate.class);
        when(mockRedis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn("1-1");

        RedisConnectionFactory factory = mock(RedisConnectionFactory.class);
        when(mockRedis.getConnectionFactory()).thenReturn(factory);
        when(factory.getConnection()).thenReturn(connection);
        return mockRedis;
    }

    @Test
    void WAITAOF_호출이_실패하면_Unconfirmed를_돌려준다() {
        LettuceConnection connection = mock(LettuceConnection.class);
        when(connection.execute(eq("WAITAOF"), any(CommandOutput.class), any(byte[].class), any(byte[].class),
                any(byte[].class))).thenThrow(new RedisConnectionFailureException("타임아웃"));

        PublishResult result = new EventPublisher(mockRedisForWaitaof(connection), PROPS, REACTION).publish(event("EP4"), 1L, "0");

        assertThat(result).isInstanceOf(PublishResult.Unconfirmed.class);
    }

    @Test
    void numlocal이_0이면_Unconfirmed를_돌려준다() {
        LettuceConnection connection = mock(LettuceConnection.class);
        when(connection.execute(eq("WAITAOF"), any(CommandOutput.class), any(byte[].class), any(byte[].class),
                any(byte[].class))).thenReturn(List.of(0L, 0L));

        PublishResult result = new EventPublisher(mockRedisForWaitaof(connection), PROPS, REACTION).publish(event("EP5"), 1L, "0");

        assertThat(result).isInstanceOf(PublishResult.Unconfirmed.class);
    }
}
