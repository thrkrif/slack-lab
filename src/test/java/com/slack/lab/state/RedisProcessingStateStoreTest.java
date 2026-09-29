package com.slack.lab.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slack.lab.queue.QueueProperties;
import com.slack.lab.state.ClaimOutcome.Claimed;
import com.slack.lab.state.ClaimOutcome.Reason;
import com.slack.lab.state.ClaimOutcome.Settled;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

/** 선점 결과표 각 행과 우선순위 조합, 종료 스크립트의 부분 실패 복구(PLAN 2단계 M11·B9·B11). */
@Testcontainers
class RedisProcessingStateStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final long LEASE_MS = 400;
    static final QueueProperties QUEUE = new QueueProperties("slack:events", "workers", 150, 100000);
    static final StateProperties STATE = new StateProperties(LEASE_MS, 100, 7, 24);
    static final long DAY_MS = Duration.ofDays(1).toMillis();

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    RedisProcessingStateStore store;

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
    }

    // --- 도우미: 메시지를 스트림에 넣고 소비 그룹으로 읽어 pending 상태로 만든다

    record Msg(String eventId, String streamId, long gen, long receivedAt) {
        ClaimRequest request() {
            return new ClaimRequest(eventId, streamId, gen, receivedAt, "C-test", "1.0");
        }
    }

    Msg deliver(String eventId, long gen, long receivedAt) {
        RecordId id = redis.opsForStream().add(MapRecord.create(QUEUE.streamKey(), Map.of(
                "event_id", eventId, "gen", String.valueOf(gen), "received_at", String.valueOf(receivedAt),
                "text", "질문 본문")));
        redis.opsForStream().read(Consumer.from(QUEUE.group(), "c1"), StreamReadOptions.empty().count(100),
                StreamOffset.create(QUEUE.streamKey(), ReadOffset.lastConsumed()));
        return new Msg(eventId, id.getValue(), gen, receivedAt);
    }

    Msg deliver(String eventId) {
        return deliver(eventId, 0, System.currentTimeMillis());
    }

    Map<Object, Object> stateOf(String eventId) {
        return redis.opsForHash().entries(RedisProcessingStateStore.stateKey(eventId));
    }

    boolean inStream(Msg m) {
        return !redis.opsForStream().range(QUEUE.streamKey(), Range.just(m.streamId())).isEmpty();
    }

    long pending() {
        return redis.opsForStream().pending(QUEUE.streamKey(), QUEUE.group()).getTotalPendingMessages();
    }

    boolean preserved(String eventId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisProcessingStateStore.preservedKey(eventId)));
    }

    boolean listed(String listKey, String eventId) {
        return redis.opsForZSet().score(listKey, eventId) != null;
    }

    Claimed claimOk(Msg m) {
        ClaimOutcome o = store.claim(m.request());
        assertThat(o).isInstanceOf(Claimed.class);
        return (Claimed) o;
    }

    static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    // retry_scheduler.lua를 직접 실행한다(worker.RetryScheduler는 패키지 전용이라 여기서 재사용할 수 없다).
    private static final org.springframework.data.redis.core.script.RedisScript<Long> SCHEDULER_SCRIPT;
    static {
        var s = new org.springframework.data.redis.core.script.DefaultRedisScript<Long>();
        s.setLocation(new org.springframework.core.io.ClassPathResource("state/retry_scheduler.lua"));
        s.setResultType(Long.class);
        SCHEDULER_SCRIPT = s;
    }

    void runSchedulerOnce() {
        redis.execute(SCHEDULER_SCRIPT, List.of(RedisProcessingStateStore.RETRY_KEY, QUEUE.streamKey()), "0", "50",
                "slack:preserved:", "slack:evt:");
    }

    // --- 8행: 새 이벤트 선점과 정상 완료

    @Test
    void 새_이벤트는_선점되고_완료_기록_뒤에야_ACK와_삭제가_된다() {
        Msg m = deliver("E1");
        Claimed c = claimOk(m);
        assertThat(stateOf("E1")).containsEntry("state", "PROCESSING").containsEntry("gen", "0");
        assertThat(pending()).isEqualTo(1);

        assertThat(store.markSending("E1", c.attemptId())).isTrue();
        assertThat(store.finalizeAttempt("E1", c.attemptId(), m.streamId(), Finalization.completed("111.1", "answer")))
                .isTrue();

        assertThat(stateOf("E1")).containsEntry("state", "COMPLETED").containsEntry("slack_ts", "111.1");
        assertThat(redis.getExpire(RedisProcessingStateStore.stateKey("E1"))).isBetween(DAY_MS / 1000 * 6, DAY_MS / 1000 * 7);
        assertThat(pending()).isZero();
        assertThat(inStream(m)).isFalse();
    }

    @Test
    void 동시에_같은_이벤트를_선점하면_하나만_이긴다() throws Exception {
        List<Msg> msgs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            msgs.add(deliver("E-dup"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(10);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimOutcome>> results = new ArrayList<>();
        for (Msg m : msgs) {
            Callable<ClaimOutcome> task = () -> {
                start.await();
                return store.claim(m.request());
            };
            results.add(pool.submit(task));
        }
        start.countDown();
        int claimed = 0;
        int busy = 0;
        for (Future<ClaimOutcome> f : results) {
            ClaimOutcome o = f.get();
            claimed += o instanceof Claimed ? 1 : 0;
            busy += o instanceof ClaimOutcome.Busy ? 1 : 0;
        }
        pool.shutdown();
        assertThat(claimed).isEqualTo(1);
        assertThat(busy).isEqualTo(9);
    }

    @Test
    void 소유자가_아니면_어떤_전이도_거절된다() {
        Msg m = deliver("E2");
        claimOk(m);
        assertThat(store.markSending("E2", "someone-else")).isFalse();
        assertThat(store.renew("E2", "someone-else")).isFalse();
        assertThat(store.finalizeAttempt("E2", "someone-else", m.streamId(), Finalization.dead("answer", "x")))
                .isFalse();
        assertThat(stateOf("E2")).containsEntry("state", "PROCESSING");
        assertThat(pending()).isEqualTo(1);
    }

    // --- 2·2'·2''행

    @Test
    void 완료된_이벤트의_재전달은_실행하지_않고_ACK한다() {
        Msg m = deliver("E3");
        Claimed c = claimOk(m);
        store.markSending("E3", c.attemptId());
        store.finalizeAttempt("E3", c.attemptId(), m.streamId(), Finalization.completed("1", "answer"));

        Msg again = deliver("E3");
        assertThat(store.claim(again.request())).isEqualTo(new Settled(Reason.DONE));
        assertThat(inStream(again)).isFalse();
    }

    @Test
    void 사람이_닫은_건에_늦은_재전송이_와도_다시_보존하지_않는다() {
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E4"),
                Map.of("state", "CLOSED", "gen", "0", "attempt_id", "a"));
        Msg late = deliver("E4");
        assertThat(store.claim(late.request())).isEqualTo(new Settled(Reason.DONE));
        assertThat(preserved("E4")).isFalse();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E4")).isFalse();
    }

    @Test
    void 이전_세대의_UNKNOWN_재전달은_재보존하지_않는다() {
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E5"),
                Map.of("state", "UNKNOWN", "gen", "1", "attempt_id", "a"));
        Msg old = deliver("E5", 0, System.currentTimeMillis());
        assertThat(store.claim(old.request())).isEqualTo(new Settled(Reason.DONE));
        assertThat(preserved("E5")).isFalse();
    }

    // --- 3·6행

    @Test
    void 예약된_재시도보다_이른_메시지와_이전_세대는_STALE이다() {
        long future = System.currentTimeMillis() + 60_000;
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E6"),
                Map.of("state", "RETRY_WAIT", "gen", "1", "retry_at", String.valueOf(future), "attempt_id", "a"));
        assertThat(store.claim(deliver("E6", 0, System.currentTimeMillis()).request()))
                .isEqualTo(new Settled(Reason.STALE));
        assertThat(store.claim(deliver("E6", 1, System.currentTimeMillis()).request()))
                .isEqualTo(new Settled(Reason.STALE));
        assertThat(pending()).isZero();
    }

    @Test
    void 도래한_재시도는_같은_세대로_선점된다() {
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E7"),
                Map.of("state", "RETRY_WAIT", "gen", "1", "retry_at", "0", "attempt_id", "a",
                        "first_received_at", String.valueOf(System.currentTimeMillis())));
        Claimed c = claimOk(deliver("E7", 1, System.currentTimeMillis()));
        assertThat(c.gen()).isEqualTo(1);
        assertThat(c.manualRun()).isFalse();
    }

    // --- 4·5행과 임대

    @Test
    void 발신_중_임대가_만료되면_UNKNOWN으로_가고_입력을_복구_목록에_보존한다() throws Exception {
        Msg m = deliver("E8");
        Claimed c = claimOk(m);
        assertThat(store.markSending("E8", c.attemptId())).isTrue();
        sleep(LEASE_MS + 100);

        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.UNKNOWN));
        assertThat(stateOf("E8")).containsEntry("state", "UNKNOWN");
        assertThat(redis.getExpire(RedisProcessingStateStore.stateKey("E8"))).isEqualTo(-1);
        assertThat(redis.opsForHash().get(RedisProcessingStateStore.preservedKey("E8"), "text")).isEqualTo("질문 본문");
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E8")).isTrue();
        assertThat(inStream(m)).isFalse();
    }

    @Test
    void 늦게_도착한_같은_시도의_성공은_UNKNOWN을_COMPLETED로_바꾸고_보존본을_지운다() throws Exception {
        Msg m = deliver("E9");
        Claimed c = claimOk(m);
        store.markSending("E9", c.attemptId());
        sleep(LEASE_MS + 100);
        store.claim(m.request());

        assertThat(store.finalizeAttempt("E9", c.attemptId(), m.streamId(), Finalization.completed("9.9", "answer")))
                .isTrue();
        assertThat(stateOf("E9")).containsEntry("state", "COMPLETED");
        assertThat(preserved("E9")).isFalse();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E9")).isFalse();
    }

    @Test
    void 처리_중_임대가_만료되면_새_시도가_재선점하고_이전_소유자는_발신할_수_없다() throws Exception {
        Msg m = deliver("E10");
        Claimed first = claimOk(m);
        sleep(LEASE_MS + 100);
        assertThat(store.renew("E10", first.attemptId())).isFalse();

        Claimed second = claimOk(m);
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(store.markSending("E10", first.attemptId())).isFalse();
        assertThat(store.markSending("E10", second.attemptId())).isTrue();
    }

    @Test
    void 임대가_유효하면_갱신되고_BUSY로_지켜진다() throws Exception {
        Msg m = deliver("E11");
        Claimed c = claimOk(m);
        for (int i = 0; i < 4; i++) {
            sleep(LEASE_MS / 2);
            assertThat(store.renew("E11", c.attemptId())).isTrue();
        }
        assertThat(store.claim(m.request())).isInstanceOf(ClaimOutcome.Busy.class);
        assertThat(pending()).isEqualTo(1);
    }

    // --- 7행과 수동 승인(4'행)

    @Test
    void 최초_수신_후_24시간이_지나면_자동_실행하지_않고_DLQ로_보낸다() {
        Msg m = deliver("E12", 0, System.currentTimeMillis() - DAY_MS - 60_000);
        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.EXPIRED));
        assertThat(stateOf("E12")).containsEntry("state", "DEAD").containsEntry("stage", "window_expired");
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E12")).isTrue();
        assertThat(preserved("E12")).isTrue();
    }

    @Test
    void 우선순위_조합에서_24시간_판정은_실행_가능한_행에만_적용된다() throws Exception {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;
        // 만료된 SENDING + 24시간 초과 → UNKNOWN(4행이 7행보다 먼저)
        Msg s = deliver("E13", 0, old);
        Claimed c = (Claimed) store.claim(new ClaimRequest("E13", s.streamId(), 0, System.currentTimeMillis(), "C", "1"));
        store.markSending("E13", c.attemptId());
        sleep(LEASE_MS + 100);
        assertThat(store.claim(s.request())).isEqualTo(new Settled(Reason.UNKNOWN));

        // COMPLETED + 24시간 초과 → DONE
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E14"), Map.of("state", "COMPLETED", "gen", "0"));
        assertThat(store.claim(deliver("E14", 0, old).request())).isEqualTo(new Settled(Reason.DONE));

        // 이전 세대 + 24시간 초과 → STALE
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E15"),
                Map.of("state", "RETRY_WAIT", "gen", "2", "retry_at", "0"));
        assertThat(store.claim(deliver("E15", 1, old).request())).isEqualTo(new Settled(Reason.STALE));
    }

    @Test
    void 승인된_세대는_24시간이_지나도_한_번_실행되고_승인은_소비된다() {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E16"), Map.of("state", "RETRY_WAIT", "gen", "1",
                "retry_at", "0", "manual_gen", "1", "first_received_at", String.valueOf(old)));
        Claimed c = claimOk(deliver("E16", 1, old));
        assertThat(c.manualRun()).isTrue();
        assertThat(stateOf("E16")).doesNotContainKey("manual_gen").containsEntry("manual_run", "1");
    }

    @Test
    void 승인된_실행이_소실되면_24시간_이내라도_새_승인_없이는_다시_돌리지_않는다() throws Exception {
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E17"), Map.of("state", "RETRY_WAIT", "gen", "1",
                "retry_at", "0", "manual_gen", "1", "first_received_at", String.valueOf(System.currentTimeMillis())));
        Msg m = deliver("E17", 1, System.currentTimeMillis());
        claimOk(m);
        sleep(LEASE_MS + 100);

        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.DEAD));
        assertThat(stateOf("E17")).containsEntry("state", "DEAD").containsEntry("stage", "manual_attempt_lost");
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E17")).isTrue();
    }

    // --- 1행

    @Test
    void 상태보다_큰_세대는_ANOMALY로_DLQ에_보존한다() {
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E18"), Map.of("state", "PROCESSING", "gen", "0"));
        assertThat(store.claim(deliver("E18", 3, System.currentTimeMillis()).request()))
                .isEqualTo(new Settled(Reason.ANOMALY));
        assertThat(stateOf("E18")).containsEntry("state", "PROCESSING");
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E18")).isTrue();
    }

    @Test
    void 상태가_없으면_세대가_0보다_커도_1행을_건너뛰고_선점한다() {
        Claimed c = claimOk(deliver("E19", 2, System.currentTimeMillis()));
        assertThat(c.gen()).isEqualTo(2);
    }

    // --- finalize 원자성: 검증 실패 무적용, 쓰기 사이 실패 후 재전달 복구

    @Test
    void 보존할_본문이_없으면_종료를_거절하고_아무것도_쓰지_않는다() {
        Msg m = deliver("E20");
        Claimed c = claimOk(m);
        store.markSending("E20", c.attemptId());
        assertThat(store.finalizeAttempt("E20", c.attemptId(), "0-1", Finalization.unknown("answer", "send_timeout")))
                .isFalse();
        assertThat(stateOf("E20")).containsEntry("state", "SENDING");
        assertThat(preserved("E20")).isFalse();
    }

    @Test
    void 보존_직후_실패해도_상태는_그대로고_다시_실행하면_한_번만_보존된다() {
        Msg m = deliver("E21");
        Claimed c = claimOk(m);
        store.markSending("E21", c.attemptId());

        store.injectFailureAfter(1); // HSET 보존 뒤 실패
        assertThatThrownBy(() -> store.finalizeAttempt("E21", c.attemptId(), m.streamId(),
                Finalization.unknown("answer", "send_timeout"))).hasStackTraceContaining("INJECTED_FAILURE");
        assertThat(stateOf("E21")).containsEntry("state", "SENDING");
        assertThat(pending()).isEqualTo(1);

        store.injectFailureAfter(0);
        assertThat(store.finalizeAttempt("E21", c.attemptId(), m.streamId(),
                Finalization.unknown("answer", "send_timeout"))).isTrue();
        assertThat(stateOf("E21")).containsEntry("state", "UNKNOWN");
        assertThat(redis.opsForZSet().size(RedisProcessingStateStore.RECOVERY_KEY)).isEqualTo(1);
        assertThat(pending()).isZero();
    }

    @Test
    void 상태_기록_뒤_ACK_전에_실패하면_재전달이_보존을_확인하고_ACK한다() {
        Msg m = deliver("E22");
        Claimed c = claimOk(m);
        store.markSending("E22", c.attemptId());

        store.injectFailureAfter(3); // 보존 HSET·ZADD, 상태 HSET 뒤 실패 — ACK 전
        assertThatThrownBy(() -> store.finalizeAttempt("E22", c.attemptId(), m.streamId(),
                Finalization.dead("failure_notice", "notice_send:channel_not_found")))
                .hasStackTraceContaining("INJECTED_FAILURE");
        assertThat(stateOf("E22")).containsEntry("state", "DEAD");
        assertThat(pending()).isEqualTo(1);

        store.injectFailureAfter(0);
        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.DONE));
        assertThat(pending()).isZero();
        assertThat(preserved("E22")).isTrue();
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E22")).isTrue();
    }

    @Test
    void 상태만_기록되고_보존이_빠진_UNKNOWN은_재전달_때_보존을_채운다() {
        // 2'행: 보존 없이 UNKNOWN이 된 경우(이전 버전 기록 등)에도 입력을 잃지 않는다
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E23"),
                Map.of("state", "UNKNOWN", "gen", "0", "attempt_id", "a"));
        Msg m = deliver("E23");
        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.DONE));
        assertThat(preserved("E23")).isTrue();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E23")).isTrue();
    }

    @Test
    void 보존_해시만_쓰이고_목록_등록_전에_중단돼도_재전달이_목록_등록까지_마친다() {
        // 2'행: preserve()의 HSET(보존)과 ZADD(목록 등록)는 별개 쓰기다. 첫 쓰기만 성공한 채 중단되면
        // 재전달이 "해시가 있으니 끝났다"고 오판하지 않고 다시 preserve를 호출해(멱등) 목록 등록까지 마쳐야 한다.
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E24"),
                Map.of("state", "UNKNOWN", "gen", "0", "attempt_id", "a"));
        Msg m = deliver("E24");

        store.injectFailureAfter(1); // preserve의 HSET 뒤 실패 — ZADD 전
        assertThatThrownBy(() -> store.claim(m.request())).hasStackTraceContaining("INJECTED_FAILURE");
        assertThat(preserved("E24")).isTrue();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E24")).isFalse();
        assertThat(pending()).isEqualTo(1);

        store.injectFailureAfter(0);
        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.DONE));
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E24")).isTrue();
        assertThat(pending()).isZero();
    }

    @Test
    void 완료_기록_뒤_정리_전에_중단돼도_다음_확인_때_TTL과_정리를_스스로_마친다() {
        Msg m = deliver("E25");
        Claimed c = claimOk(m);
        store.markSending("E25", c.attemptId());

        store.injectFailureAfter(1); // 상태 HSET(COMPLETED) 뒤 실패 — DEL·ZREM·PEXPIRE·ACK 전
        assertThatThrownBy(() -> store.finalizeAttempt("E25", c.attemptId(), m.streamId(),
                Finalization.completed("25.1", "answer"))).hasStackTraceContaining("INJECTED_FAILURE");
        assertThat(stateOf("E25")).containsEntry("state", "COMPLETED");
        assertThat(redis.getExpire(RedisProcessingStateStore.stateKey("E25"))).isEqualTo(-1);
        assertThat(pending()).isEqualTo(1);

        store.injectFailureAfter(0);
        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.DONE)); // 자가치유
        assertThat(redis.getExpire(RedisProcessingStateStore.stateKey("E25"))).isGreaterThan(0);
        assertThat(pending()).isZero();
    }

    @Test
    void 발신_만료_복구에_다른_이벤트의_stream_id가_섞이면_보존하지_않고_NO_INPUT을_돌려준다() throws Exception {
        Msg wrong = deliver("E-other-1");
        Msg m = deliver("E26");
        Claimed c = claimOk(m);
        store.markSending("E26", c.attemptId());
        sleep(LEASE_MS + 100);

        ClaimOutcome bogus = store.claim(new ClaimRequest("E26", wrong.streamId(), 0, System.currentTimeMillis(), "C", "1"));
        assertThat(bogus).isInstanceOf(ClaimOutcome.NoInput.class);
        assertThat(stateOf("E26")).containsEntry("state", "SENDING");
        assertThat(preserved("E26")).isFalse();

        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.UNKNOWN));
    }

    @Test
    void finalize에_다른_이벤트의_stream_id를_주면_보존_없이_거절된다() {
        Msg wrong = deliver("E-other-2");
        Msg m = deliver("E27");
        Claimed c = claimOk(m);
        store.markSending("E27", c.attemptId());

        assertThat(store.finalizeAttempt("E27", c.attemptId(), wrong.streamId(),
                Finalization.unknown("answer", "send_timeout"))).isFalse();
        assertThat(stateOf("E27")).containsEntry("state", "SENDING");
        assertThat(preserved("E27")).isFalse();

        assertThat(store.finalizeAttempt("E27", c.attemptId(), m.streamId(),
                Finalization.unknown("answer", "send_timeout"))).isTrue();
    }

    @Test
    void 완료된_이벤트에_다른_이벤트의_stream_id가_섞이면_그_메시지를_ACK하지_않는다() {
        // 2행(DONE)은 원래 entry 없이 ack만 하므로, 전역 가드가 없으면 다른 이벤트의 대기 중
        // 메시지를 실수로 지울 수 있었다 — 모든 분기 앞에 둔 가드를 여기서 직접 확인한다.
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E29"), Map.of("state", "COMPLETED", "gen", "0"));
        redis.expire(RedisProcessingStateStore.stateKey("E29"), Duration.ofDays(1));
        Msg other = deliver("E-other-3");

        ClaimOutcome outcome = store.claim(new ClaimRequest("E29", other.streamId(), 0, System.currentTimeMillis(), "C", "1"));
        assertThat(outcome).isInstanceOf(ClaimOutcome.NoInput.class);
        assertThat(pending()).isEqualTo(1);
        assertThat(inStream(other)).isTrue();
    }

    @Test
    void 정상_완료는_다른_세대가_보존한_DLQ_항목을_지우지_않는다() {
        // 보존 해시는 event_id로만 키가 갈리고 gen을 담지 않는다. gen=0이 정상 완료되는 동안
        // 같은 이벤트의 미래 세대(gen=3, 비정상 입력)가 남긴 보존본을 실수로 지우면 안 된다.
        Msg m0 = deliver("E30", 0, System.currentTimeMillis());
        Claimed c = claimOk(m0);
        store.markSending("E30", c.attemptId());

        Msg future = deliver("E30", 3, System.currentTimeMillis());
        assertThat(store.claim(future.request())).isEqualTo(new Settled(Reason.ANOMALY));
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E30")).isTrue();

        assertThat(store.finalizeAttempt("E30", c.attemptId(), m0.streamId(), Finalization.completed("30.1", "answer")))
                .isTrue();
        assertThat(preserved("E30")).isTrue(); // gen=3의 보존본은 그대로 남는다
        assertThat(redis.opsForHash().get(RedisProcessingStateStore.preservedKey("E30"), "gen")).isEqualTo("3");
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E30")).isTrue();
    }

    @Test
    void 새_세대의_정상_완료는_옛_세대가_남긴_보존본을_지운다() {
        // M14 reprocess가 gen+1로 재투입해 정상 완료되면, gen(옛 세대)이 남긴 보존본은 정리돼야
        // B18(해결된 건의 본문 잔존 0)을 만족한다 — "다르면 보존"이 아니라 "미래 세대만 보존"이어야 한다.
        redis.opsForHash().putAll(RedisProcessingStateStore.preservedKey("E31"),
                Map.of("event_id", "E31", "gen", "0", "text", "옛 세대 입력"));
        redis.opsForZSet().add(RedisProcessingStateStore.DLQ_KEY, "E31", 0);
        // reprocess는 재투입 전에 상태 gen을 먼저 올린다(gen 불변식) — 여기서도 gen을 1로 미리 맞춘다.
        redis.opsForHash().putAll(RedisProcessingStateStore.stateKey("E31"),
                Map.of("state", "RETRY_WAIT", "gen", "1", "retry_at", "0",
                        "first_received_at", String.valueOf(System.currentTimeMillis())));

        Msg reprocessed = deliver("E31", 1, System.currentTimeMillis());
        Claimed c = claimOk(reprocessed);
        assertThat(c.gen()).isEqualTo(1);
        store.markSending("E31", c.attemptId());
        assertThat(store.finalizeAttempt("E31", c.attemptId(), reprocessed.streamId(),
                Finalization.completed("31.1", "answer"))).isTrue();

        assertThat(preserved("E31")).isFalse();
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E31")).isFalse();
    }

    // --- M13: scheduleRetry(재시도 예약)

    @Test
    void 재시도_예약은_안내_없이_RETRY_WAIT으로_ACK하고_세대와_재시도_횟수를_올린다() {
        Msg m = deliver("E32");
        Claimed c = claimOk(m);
        long retryAt = System.currentTimeMillis() + 60_000;

        assertThat(store.scheduleRetry("E32", c.attemptId(), m.streamId(), c.gen() + 1, retryAt, 1, "llm_timeout"))
                .isTrue();

        assertThat(stateOf("E32")).containsEntry("state", "RETRY_WAIT").containsEntry("gen", "1")
                .containsEntry("retries", "1").containsEntry("retry_at", String.valueOf(retryAt));
        assertThat(pending()).isZero(); // ACK됐다 — 안내 없이 재시도만 예약
        assertThat(inStream(m)).isFalse();
        assertThat(preserved("E32")).isTrue(); // 재투입할 입력이 보존됐다
        assertThat(redis.opsForZSet().score(RedisProcessingStateStore.RETRY_KEY, "E32")).isEqualTo((double) retryAt);
    }

    @Test
    void 예약된_시각_전에는_선점되지_않고_도래하면_다음_세대로_선점되며_재시도_횟수를_물려받는다() {
        Msg m = deliver("E33");
        Claimed c = claimOk(m);
        long retryAt = System.currentTimeMillis() + 300;
        store.scheduleRetry("E33", c.attemptId(), m.streamId(), c.gen() + 1, retryAt, 1, "llm_timeout");

        // 스케줄러가 재투입한 것처럼 gen=1로 다시 XADD한다.
        Msg requeued = deliver("E33", 1, m.receivedAt());
        assertThat(store.claim(requeued.request())).isEqualTo(new Settled(Reason.STALE)); // 아직 이르다

        try {
            sleep(400);
        } catch (InterruptedException ignored) {
        }
        Msg requeuedAgain = deliver("E33", 1, m.receivedAt());
        Claimed second = claimOk(requeuedAgain);
        assertThat(second.gen()).isEqualTo(1);
        assertThat(second.retries()).isEqualTo(1); // 예약 때 기록한 재시도 횟수를 그대로 물려받는다
    }

    @Test
    void 도래한_재시도라도_최초_수신_후_24시간이_지나면_실행하지_않고_DLQ로_보낸다() {
        Msg m = deliver("E34"); // 최초 수신은 최근 시각 — 선점 자체는 정상이어야 한다
        Claimed c = claimOk(m);
        store.scheduleRetry("E34", c.attemptId(), m.streamId(), c.gen() + 1, 0, 1, "llm_timeout"); // 이미 도래

        // 재시도 대기 중 24시간이 흘렀다고 가정한다(최초 수신 시각을 뒤로 돌린다).
        redis.opsForHash().put(RedisProcessingStateStore.stateKey("E34"), "first_received_at",
                String.valueOf(System.currentTimeMillis() - DAY_MS - 60_000));

        Msg requeued = deliver("E34", 1, m.receivedAt());
        assertThat(store.claim(requeued.request())).isEqualTo(new Settled(Reason.EXPIRED));
        assertThat(stateOf("E34")).containsEntry("state", "DEAD").containsEntry("stage", "window_expired");
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E34")).isTrue();
    }

    @Test
    void 완료되면_재시도_목록에_남아있던_항목도_정리된다() {
        // 재시도 스케줄러의 XADD 성공/ZREM 실패로 재시도 목록에 잔여 항목이 남을 수 있다(M13) —
        // 그 사이 다른 시도가 정상 완료되면 잔여 항목도 함께 정리돼야 한다.
        redis.opsForZSet().add(RedisProcessingStateStore.RETRY_KEY, "E35", System.currentTimeMillis());
        Msg m = deliver("E35", 0, System.currentTimeMillis());
        Claimed c = claimOk(m);
        store.markSending("E35", c.attemptId());

        assertThat(store.finalizeAttempt("E35", c.attemptId(), m.streamId(), Finalization.completed("35.1", "answer")))
                .isTrue();
        assertThat(listed(RedisProcessingStateStore.RETRY_KEY, "E35")).isFalse();
    }

    @Test
    void 재시도_예약_부분_실패_뒤_다시_호출하면_멱등하게_완결되고_잔존물이_없다() throws InterruptedException {
        // codex critic MAJOR-1(수정 1/2): preserve_at()이 raw를 그대로(옛 gen) 보존한 뒤 별도 HSET으로
        // gen을 고치던 2단계 방식을, next_gen을 미리 섞은 배열을 preserve_at에 한 번에 넘기는 방식으로
        // 바꿨다 — claim()·finalize()처럼 "처음부터 올바른 값으로 한 번에 쓴다." 각 쓰기 지점(HSET·ZADD·
        // 상태 HSET)마다 실패를 주입한 뒤 같은 인자로 재시도(멱등)하면 next_gen으로 정상 완료되고,
        // 보존본·DLQ·재시도 목록에 잔존물이 남지 않아야 한다(B18).
        for (int failAfter = 1; failAfter <= 3; failAfter++) {
            String eventId = "E40-" + failAfter;
            Msg m = deliver(eventId);
            Claimed c = claimOk(m);
            long retryAt = System.currentTimeMillis() + 100;

            store.injectFailureAfter(failAfter);
            assertThatThrownBy(() -> store.scheduleRetry(eventId, c.attemptId(), m.streamId(), c.gen() + 1, retryAt,
                    1, "llm_timeout")).hasStackTraceContaining("INJECTED_FAILURE");
            store.injectFailureAfter(0);

            // 상태 HSET(write3)까지 끝났으면(failAfter==3) 이미 RETRY_WAIT이라 같은 attempt_id로는 더 이상
            // 재시도를 받아주지 않는다(state.lua 가드가 PROCESSING/SENDING만 허용) — 그 경우 재호출 없이도
            // 이미 일관된 상태이므로 재확인만 한다. 그 전(failAfter<3)이면 재호출이 멱등하게 완주해야 한다.
            if (!"RETRY_WAIT".equals(stateOf(eventId).get("state"))) {
                assertThat(store.scheduleRetry(eventId, c.attemptId(), m.streamId(), c.gen() + 1, retryAt, 1,
                        "llm_timeout")).as("event=%s failAfter=%d 재시도", eventId, failAfter).isTrue();
            }
            assertThat(stateOf(eventId)).as("event=%s failAfter=%d", eventId, failAfter)
                    .containsEntry("state", "RETRY_WAIT").containsEntry("gen", "1");
            assertThat(redis.opsForHash().get(RedisProcessingStateStore.preservedKey(eventId), "gen"))
                    .as("event=%s failAfter=%d 보존본 gen", eventId, failAfter).isEqualTo("1");

            // 스케줄러 개입 여부와 무관하게 gen 일관성 자체를 확인한다 — 재투입 경로는 별도 테스트가 다룬다.
            sleep(150); // retry_at 도래
            Msg requeued = deliver(eventId, 1, m.receivedAt());
            Claimed second = claimOk(requeued);
            assertThat(second.gen()).as("event=%s failAfter=%d", eventId, failAfter).isEqualTo(1);
            store.markSending(eventId, second.attemptId());
            assertThat(store.finalizeAttempt(eventId, second.attemptId(), requeued.streamId(),
                    Finalization.completed("40.1", "answer"))).isTrue();

            assertThat(stateOf(eventId)).as("event=%s failAfter=%d state", eventId, failAfter)
                    .containsEntry("state", "COMPLETED");
            assertThat(preserved(eventId)).as("event=%s failAfter=%d 보존본 잔존", eventId, failAfter).isFalse();
            assertThat(listed(RedisProcessingStateStore.DLQ_KEY, eventId)).as("event=%s failAfter=%d DLQ 잔존",
                    eventId, failAfter).isFalse();
            assertThat(listed(RedisProcessingStateStore.RETRY_KEY, eventId)).as("event=%s failAfter=%d 재시도목록 잔존",
                    eventId, failAfter).isFalse();
        }
    }

    @Test
    void 재시도_예약이_상태_기록_전에_끊기면_스케줄러는_재투입하지_않고_재선점이_원래_세대로_수습한다() throws InterruptedException {
        // codex critic MAJOR-1(수정 2/2, retry_scheduler.lua): preserve(HSET+ZADD)가 상태 HSET보다 먼저
        // 끝나는 순서(M11 "검증→보존→상태→ACK" 원칙) 자체는 유지해야 한다 — 순서를 반대로 하면 상태만
        // RETRY_WAIT로 앞서가고 보존이 비어(또는 옛 gen인 채) 있어 영영 재투입되지 않는 정지(stall) 위험이
        // 더 크다. 대신 스케줄러가 XADD 전에 상태 해시를 확인해, RETRY_WAIT으로 확정되기 전에는(원래 시도가
        // 아직 PROCESSING/SENDING) 재투입을 건너뛴다 — 그렇지 않으면 아직 old_gen인 상태 해시에 next_gen
        // 메시지가 들어가 claim()이 ANOMALY로 오판하고, 원래 시도가 old_gen으로 정상 완료된 뒤에도
        // (완료 gen < 보존 gen이라) 청소되지 않는 고아를 남긴다.
        Msg m = deliver("E41");
        Claimed c = claimOk(m);
        long retryAt = System.currentTimeMillis() - 1; // 이미 도래

        store.injectFailureAfter(2); // preserve(HSET+ZADD) 끝, 상태 HSET 전 — ZADD로 목록엔 이미 올라감
        assertThatThrownBy(() -> store.scheduleRetry("E41", c.attemptId(), m.streamId(), c.gen() + 1, retryAt, 1,
                "llm_timeout")).hasStackTraceContaining("INJECTED_FAILURE");
        store.injectFailureAfter(0);

        assertThat(listed(RedisProcessingStateStore.RETRY_KEY, "E41")).isTrue(); // ZADD는 이미 성공했다
        assertThat(stateOf("E41")).containsEntry("state", "PROCESSING").containsEntry("gen", "0"); // 상태는 아직 안 바뀜

        runSchedulerOnce(); // 상태가 RETRY_WAIT이 아니므로 재투입하지 않고 건너뛴다
        assertThat(pending()).isEqualTo(1); // 원본 메시지 하나만 있다 — 잘못된 next_gen 메시지가 추가되지 않았다
        assertThat(listed(RedisProcessingStateStore.RETRY_KEY, "E41")).isTrue(); // 목록에서도 지우지 않고 다음 주기를 기다린다

        // 원래 시도의 임대가 만료되면 재선점이 같은(old) 세대로 자연히 수습한다.
        sleep(LEASE_MS + 100);
        ClaimOutcome reclaim = store.claim(m.request());
        assertThat(reclaim).isInstanceOf(Claimed.class);
        assertThat(((Claimed) reclaim).gen()).isZero(); // next_gen이 아니라 원래 세대 그대로

        store.markSending("E41", ((Claimed) reclaim).attemptId());
        assertThat(store.finalizeAttempt("E41", ((Claimed) reclaim).attemptId(), m.streamId(),
                Finalization.completed("41.1", "answer"))).isTrue();
        assertThat(stateOf("E41")).containsEntry("state", "COMPLETED");

        runSchedulerOnce(); // 이제 상태가 COMPLETED이니 남아있던 재시도 목록 항목을 정리(GC)한다
        assertThat(listed(RedisProcessingStateStore.RETRY_KEY, "E41")).isFalse();
        assertThat(preserved("E41")).isFalse();
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "E41")).isFalse();
    }

    @Test
    void 소유자가_아니거나_보존할_입력이_없으면_재시도_예약이_거절되고_아무것도_쓰지_않는다() {
        Msg m = deliver("E36");
        Claimed c = claimOk(m);

        assertThat(store.scheduleRetry("E36", "someone-else", m.streamId(), c.gen() + 1, 0, 1, "x")).isFalse();
        assertThat(stateOf("E36")).containsEntry("state", "PROCESSING");
        assertThat(preserved("E36")).isFalse();
    }

    @Test
    void 자가_재완료는_재전달_없이_finalize_재호출만으로_잔여_정리를_마친다() throws Exception {
        // 늦은 완료가 정리 전에 중단되면, 이 지점을 다시 겨냥할 큐 메시지가 없다(스트림 항목은
        // UNKNOWN 전이 때 이미 삭제됐다) — 재전달에 기대지 않고 같은 인자로 finalize를 다시 부르면
        // (self_recomplete) 자가치유된다.
        Msg m = deliver("E28");
        Claimed c = claimOk(m);
        store.markSending("E28", c.attemptId());
        sleep(LEASE_MS + 100);
        store.claim(m.request()); // UNKNOWN, 입력을 복구 목록에 보존, 스트림 항목은 이미 삭제됨

        store.injectFailureAfter(1); // 늦은 완료: 상태 HSET(COMPLETED) 뒤 실패 — 정리·TTL·ACK 전
        assertThatThrownBy(() -> store.finalizeAttempt("E28", c.attemptId(), "",
                Finalization.completed("28.1", "answer"))).hasStackTraceContaining("INJECTED_FAILURE");
        assertThat(stateOf("E28")).containsEntry("state", "COMPLETED");
        assertThat(preserved("E28")).isTrue();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E28")).isTrue();

        store.injectFailureAfter(0);
        assertThat(store.finalizeAttempt("E28", c.attemptId(), "", Finalization.completed("28.1", "answer"))).isTrue();
        assertThat(preserved("E28")).isFalse();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "E28")).isFalse();
        assertThat(redis.getExpire(RedisProcessingStateStore.stateKey("E28"))).isGreaterThan(0);
    }
}
