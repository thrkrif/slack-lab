package com.slack.lab.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.queue.QueueProperties;
import com.slack.lab.state.ClaimOutcome;
import com.slack.lab.state.ClaimOutcome.Claimed;
import com.slack.lab.state.ClaimRequest;
import com.slack.lab.state.Finalization;
import com.slack.lab.state.RedisProcessingStateStore;
import com.slack.lab.state.StateProperties;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 재시도 스케줄러의 XADD 성공 → ZREM 순서와 부분 실패 복구(M13, PLAN "재시도 스케줄러" 절).
 * fail_after 주입으로 XADD 뒤 ZREM 전에 중단시켜 입력 보존과 중복 실행 0을 확인한다.
 */
@Testcontainers
class RetrySchedulerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final QueueProperties QUEUE = new QueueProperties("slack:events", "workers", 150, 100000);
    static final StateProperties STATE = new StateProperties(30000, 10000, 7, 24);

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    RedisProcessingStateStore store;
    RetryScheduler scheduler;

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
        redis.opsForStream().createGroup(QUEUE.streamKey(), ReadOffset.from("0"), QUEUE.group());
        store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        scheduler = new RetryScheduler(redis, QUEUE);
    }

    private RecordId deliverAndClaim(String eventId) {
        RecordId id = redis.opsForStream().add(MapRecord.create(QUEUE.streamKey(), Map.of(
                "event_id", eventId, "gen", "0", "received_at", String.valueOf(System.currentTimeMillis()),
                "text", "질문 본문")));
        redis.opsForStream().read(Consumer.from(QUEUE.group(), "c1"), StreamReadOptions.empty().count(100),
                StreamOffset.create(QUEUE.streamKey(), ReadOffset.lastConsumed()));
        return id;
    }

    private long pending() {
        return redis.opsForStream().pending(QUEUE.streamKey(), QUEUE.group()).getTotalPendingMessages();
    }

    /** XADD만으로는 소비 그룹의 pending에 잡히지 않는다 — 스트림에 새로 들어온 개수 자체를 센다. */
    private long streamLength() {
        return redis.opsForStream().size(QUEUE.streamKey());
    }

    @Test
    void 도래한_재시도를_스트림에_재투입하고_목록에서_지운다() {
        RecordId id = deliverAndClaim("R1");
        Claimed c = (Claimed) store.claim(new ClaimRequest("R1", id.getValue(), 0, System.currentTimeMillis(), "C", "1"));
        store.scheduleRetry("R1", c.attemptId(), id.getValue(), 1, 0, 1, "llm_timeout"); // 이미 도래(0)

        scheduler.runOnce();

        assertThat(streamLength()).isEqualTo(1); // 재투입된 메시지가 스트림에 새로 들어갔다
        assertThat(redis.opsForZSet().score(RedisProcessingStateStore.RETRY_KEY, "R1")).isNull();
    }

    @Test
    void 아직_도래하지_않은_재시도는_건드리지_않는다() {
        RecordId id = deliverAndClaim("R2");
        Claimed c = (Claimed) store.claim(new ClaimRequest("R2", id.getValue(), 0, System.currentTimeMillis(), "C", "1"));
        long farFuture = System.currentTimeMillis() + 60_000;
        store.scheduleRetry("R2", c.attemptId(), id.getValue(), 1, farFuture, 1, "llm_timeout");

        scheduler.runOnce();

        assertThat(pending()).isZero(); // 재투입되지 않았다
        assertThat(redis.opsForZSet().score(RedisProcessingStateStore.RETRY_KEY, "R2")).isEqualTo((double) farFuture);
    }

    @Test
    void XADD_성공_뒤_ZREM_전에_실패해도_입력은_보존되고_재실행은_되지_않는다() {
        // fail_after=1: write()로 계산했을 때 첫 번째 쓰기(XADD)까지는 성공하고 그 뒤(ZREM 전)에 주입 실패.
        RecordId id = deliverAndClaim("R3");
        Claimed c = (Claimed) store.claim(new ClaimRequest("R3", id.getValue(), 0, System.currentTimeMillis(), "C", "1"));
        store.scheduleRetry("R3", c.attemptId(), id.getValue(), 1, 0, 1, "llm_timeout");

        scheduler.injectFailureAfter(1);
        scheduler.runOnce(); // 예외를 던지지 않는다 — runOnce()가 RuntimeException을 잡아 로그만 남긴다

        assertThat(streamLength()).isEqualTo(1); // XADD는 성공해 메시지가 재투입됐다
        assertThat(redis.opsForZSet().score(RedisProcessingStateStore.RETRY_KEY, "R3")).isNotNull(); // ZREM은 못했다

        // 다음 주기(실패 주입 없이)에 같은 event_id를 다시 XADD한다 — 중복이 생긴다.
        scheduler.injectFailureAfter(0);
        scheduler.runOnce();
        assertThat(streamLength()).isEqualTo(2); // R3 메시지가 스트림에 두 번 들어갔다(gen=1이 중복)
        assertThat(redis.opsForZSet().score(RedisProcessingStateStore.RETRY_KEY, "R3")).isNull(); // 이제 목록에서는 지워졌다

        // 첫 번째 중복 메시지를 읽어 claim() → PROCESSING으로 전이하고 완료시킨다.
        var records = redis.opsForStream().read(Consumer.from(QUEUE.group(), "c1"),
                StreamReadOptions.empty().count(10), StreamOffset.create(QUEUE.streamKey(), ReadOffset.lastConsumed()));
        assertThat(records).hasSize(2);
        String firstStreamId = records.get(0).getId().getValue();
        String secondStreamId = records.get(1).getId().getValue();

        ClaimOutcome first = store.claim(new ClaimRequest("R3", firstStreamId, 1, System.currentTimeMillis(), "C", "1"));
        assertThat(first).isInstanceOf(Claimed.class);
        String attemptId = ((Claimed) first).attemptId();
        store.markSending("R3", attemptId);
        store.finalizeAttempt("R3", attemptId, firstStreamId, Finalization.completed("1.1", "answer"));

        // 두 번째(중복) 메시지는 이미 완료된 것으로 판정돼 다시 실행되지 않는다 — 중복 실행 0.
        ClaimOutcome second = store.claim(new ClaimRequest("R3", secondStreamId, 1, System.currentTimeMillis(), "C", "1"));
        assertThat(second).isEqualTo(new ClaimOutcome.Settled(ClaimOutcome.Reason.DONE));
    }
}
