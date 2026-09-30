package com.slack.lab.state;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.queue.QueueProperties;
import com.slack.lab.state.ClaimOutcome.Claimed;
import com.slack.lab.state.ClaimOutcome.Reason;
import com.slack.lab.state.ClaimOutcome.Settled;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/** 사람이 부르는 복구 전이(M14): resolve·close·reprocess, 승인 1회 소비(B11), 부분 실패 뒤 재실행, 잔존물 없음(B18). */
@Testcontainers
class RecoveryStoreTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final long LEASE_MS = 400;
    static final QueueProperties QUEUE = new QueueProperties("slack:events", "workers", 150, 100000);
    static final StateProperties STATE = new StateProperties(LEASE_MS, 100, 7, 24);
    static final long DAY_MS = Duration.ofDays(1).toMillis();

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;
    RedisProcessingStateStore store;
    RecoveryStore recovery;

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
        recovery = new RecoveryStore(redis, QUEUE, STATE);
    }

    // --- 도우미

    record Msg(String eventId, String streamId, long gen, long receivedAt) {
        ClaimRequest request() {
            return new ClaimRequest(eventId, streamId, gen, receivedAt, "C-test", "1.0");
        }
    }

    Msg deliver(String eventId, long gen, long receivedAt, Map<String, String> extra) {
        Map<String, String> fields = new HashMap<>(extra);
        fields.put("event_id", eventId);
        fields.put("gen", String.valueOf(gen));
        fields.put("received_at", String.valueOf(receivedAt));
        fields.put("text", "질문 본문");
        RecordId id = redis.opsForStream().add(MapRecord.create(QUEUE.streamKey(), fields));
        redis.opsForStream().read(Consumer.from(QUEUE.group(), "c1"), StreamReadOptions.empty().count(100),
                StreamOffset.create(QUEUE.streamKey(), ReadOffset.lastConsumed()));
        return new Msg(eventId, id.getValue(), gen, receivedAt);
    }

    Msg deliver(String eventId) {
        return deliver(eventId, 0, System.currentTimeMillis(), Map.of());
    }

    /** reprocess가 XADD한 메시지를 소비 그룹으로 읽어 온다(워커가 하는 일). */
    List<Msg> readNew() {
        List<MapRecord<String, Object, Object>> recs = redis.opsForStream().read(Consumer.from(QUEUE.group(), "c1"),
                StreamReadOptions.empty().count(100), StreamOffset.create(QUEUE.streamKey(), ReadOffset.lastConsumed()));
        List<Msg> out = new ArrayList<>();
        if (recs != null) {
            for (var r : recs) {
                out.add(new Msg(String.valueOf(r.getValue().get("event_id")), r.getId().getValue(),
                        Long.parseLong(String.valueOf(r.getValue().get("gen"))),
                        Long.parseLong(String.valueOf(r.getValue().get("received_at")))));
            }
        }
        return out;
    }

    Map<Object, Object> stateOf(String eventId) {
        return redis.opsForHash().entries(RedisProcessingStateStore.stateKey(eventId));
    }

    boolean preserved(String eventId) {
        return Boolean.TRUE.equals(redis.hasKey(RedisProcessingStateStore.preservedKey(eventId)));
    }

    boolean listed(String listKey, String eventId) {
        return redis.opsForZSet().score(listKey, eventId) != null;
    }

    long streamLength() {
        Long n = redis.opsForStream().size(QUEUE.streamKey());
        return n == null ? 0 : n;
    }

    Claimed claimOk(Msg m) {
        ClaimOutcome o = store.claim(m.request());
        assertThat(o).isInstanceOf(Claimed.class);
        return (Claimed) o;
    }

    /** 발신 직후 끊긴 건: 발신 중 상태에서 결과 불명으로 종료한다. */
    Msg makeUnknown(String eventId) {
        Msg m = deliver(eventId);
        Claimed c = claimOk(m);
        assertThat(store.markSending(eventId, c.attemptId())).isTrue();
        assertThat(store.finalizeAttempt(eventId, c.attemptId(), m.streamId(),
                Finalization.unknown("answer", "answer_send:read_timeout"))).isTrue();
        return m;
    }

    Msg makeDead(String eventId, long receivedAt) {
        Msg m = deliver(eventId, 0, receivedAt, Map.of());
        Claimed c = claimOk(m);
        assertThat(store.finalizeAttempt(eventId, c.attemptId(), m.streamId(),
                Finalization.dead("answer", "answer_send:invalid_auth"))).isTrue();
        return m;
    }

    void assertNoResidue(String eventId) {
        assertThat(preserved(eventId)).isFalse();
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, eventId)).isFalse();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, eventId)).isFalse();
        assertThat(listed(RedisProcessingStateStore.RETRY_KEY, eventId)).isFalse();
    }

    // --- list

    @Test
    void 목록은_UNKNOWN과_DEAD를_상태와_함께_돌려주고_본문은_담지_않는다() {
        makeUnknown("R1");
        makeDead("R2", System.currentTimeMillis());

        var entries = recovery.list();

        assertThat(entries).extracting(RecoveryStore.Entry::eventId).containsExactlyInAnyOrder("R1", "R2");
        assertThat(entries).filteredOn(e -> e.eventId().equals("R1")).singleElement().satisfies(e -> {
            assertThat(e.list()).isEqualTo("recovery");
            assertThat(e.state()).containsEntry("state", "UNKNOWN");
        });
        assertThat(entries).filteredOn(e -> e.eventId().equals("R2")).singleElement()
                .satisfies(e -> assertThat(e.list()).isEqualTo("dlq"));
        assertThat(entries.toString()).doesNotContain("질문 본문");
    }

    @Test
    void 스레드_위치는_thread_ts가_있으면_그것을_없으면_ts를_루트로_쓴다() {
        makeUnknown("R3");
        redis.opsForHash().putAll(RedisProcessingStateStore.preservedKey("R3"),
                Map.of("channel", "C1", "ts", "100.1", "thread_ts", ""));
        assertThat(recovery.threadRef("R3")).contains(new RecoveryStore.ThreadRef("C1", "100.1"));

        redis.opsForHash().put(RedisProcessingStateStore.preservedKey("R3"), "thread_ts", "50.5");
        assertThat(recovery.threadRef("R3")).contains(new RecoveryStore.ThreadRef("C1", "50.5"));
    }

    // --- resolve-completed · close

    @Test
    void resolve_completed는_COMPLETED로_바꾸고_보존본과_목록과_TTL을_정리한다() {
        makeUnknown("R4");

        RecoveryOutcome out = recovery.resolveCompleted("R4", "300.1");

        assertThat(out.ok()).isTrue();
        assertThat(stateOf("R4")).containsEntry("state", "COMPLETED").containsEntry("slack_ts", "300.1")
                .containsEntry("stage", "manual_resolved");
        assertNoResidue("R4");
        Long ttl = redis.getExpire(RedisProcessingStateStore.stateKey("R4"), java.util.concurrent.TimeUnit.SECONDS);
        assertThat(ttl).isGreaterThan(0);
    }

    @Test
    void resolve_completed는_같은_ts로_다시_실행해도_멱등이고_다른_ts는_거절한다() {
        makeUnknown("R5");
        assertThat(recovery.resolveCompleted("R5", "300.1").ok()).isTrue();

        assertThat(recovery.resolveCompleted("R5", "300.1").ok()).isTrue();
        RecoveryOutcome conflict = recovery.resolveCompleted("R5", "999.9");
        assertThat(conflict.status()).isEqualTo("CONFLICT");
        assertThat(stateOf("R5")).containsEntry("slack_ts", "300.1");
    }

    @Test
    void 처리_중이거나_없는_건은_resolve와_reprocess가_거절된다() {
        Msg m = deliver("R6");
        claimOk(m);

        assertThat(recovery.resolveCompleted("R6", "1.1").status()).isEqualTo("BAD_STATE");
        assertThat(recovery.close("R6").status()).isEqualTo("BAD_STATE");
        assertThat(recovery.reprocess("R6").status()).isEqualTo("BAD_STATE");
        assertThat(recovery.close("없음").status()).isEqualTo("NOT_FOUND");
        assertThat(recovery.reprocess("없음").status()).isEqualTo("NOT_FOUND");
        assertThat(stateOf("R6")).containsEntry("state", "PROCESSING");
    }

    @Test
    void close는_CLOSED로_바꾸고_정리하며_늦은_재전달이_와도_다시_보존하지_않는다() {
        Msg m = makeDead("R7", System.currentTimeMillis());

        assertThat(recovery.close("R7").ok()).isTrue();

        assertThat(stateOf("R7")).containsEntry("state", "CLOSED");
        assertNoResidue("R7");
        Msg late = deliver("R7", 0, m.receivedAt(), Map.of());
        assertThat(store.claim(late.request())).isEqualTo(new Settled(Reason.DONE));
        assertNoResidue("R7");
    }

    @Test
    void resolve는_어느_쓰기_뒤에_끊겨도_같은_명령을_다시_실행하면_잔존물_없이_끝난다() {
        for (int failAfter = 1; failAfter <= 6; failAfter++) {
            reset();
            String id = "RF" + failAfter;
            makeUnknown(id);

            recovery.injectFailureAfter(failAfter);
            try {
                recovery.resolveCompleted(id, "300.1");
            } catch (RuntimeException expected) {
                // 주입한 실패 — 여기서 끊긴 상태가 재현 대상이다
            }
            recovery.injectFailureAfter(0);

            assertThat(recovery.resolveCompleted(id, "300.1").ok()).as("fail_after=%d 재실행", failAfter).isTrue();
            assertNoResidue(id);
            assertThat(stateOf(id)).containsEntry("state", "COMPLETED");
            assertThat(redis.getExpire(RedisProcessingStateStore.stateKey(id))).isGreaterThan(0);
        }
    }

    // --- reprocess

    @Test
    void reprocess는_gen을_올려_승인을_기록하고_원래_received_at으로_재투입한다() {
        Msg m = makeUnknown("R8");
        long before = streamLength();

        RecoveryOutcome out = recovery.reprocess("R8");

        assertThat(out.ok()).isTrue();
        assertThat(out.detail()).isEqualTo("1");
        assertThat(stateOf("R8")).containsEntry("state", "RETRY_WAIT").containsEntry("gen", "1")
                .containsEntry("manual_gen", "1").containsEntry("stage", "manual_reprocess");
        // 목록에서는 빠지지만 보존본은 남는다 — 그 실행이 COMPLETED가 될 때 지운다.
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "R8")).isFalse();
        assertThat(preserved("R8")).isTrue();
        List<Msg> re = readNew();
        assertThat(re).hasSize(1);
        assertThat(re.get(0).eventId()).isEqualTo("R8");
        assertThat(re.get(0).gen()).isEqualTo(1);
        assertThat(re.get(0).receivedAt()).isEqualTo(m.receivedAt());
        assertThat(streamLength()).isEqualTo(before + 1);
        var fields = redis.opsForStream().range(QUEUE.streamKey(),
                org.springframework.data.domain.Range.just(re.get(0).streamId())).get(0).getValue();
        assertThat(fields).doesNotContainKey("preserved_reason");
    }

    @Test
    void 승인_실행이_완료되면_보존본과_목록이_지워지고_승인은_소비된다() {
        makeUnknown("R9");
        recovery.reprocess("R9");
        Msg re = readNew().get(0);

        Claimed c = claimOk(re);

        assertThat(c.manualRun()).isTrue();
        assertThat(c.gen()).isEqualTo(1);
        assertThat(stateOf("R9")).doesNotContainKey("manual_gen").containsEntry("manual_run", "1");
        assertThat(store.markSending("R9", c.attemptId())).isTrue();
        assertThat(store.finalizeAttempt("R9", c.attemptId(), re.streamId(), Finalization.completed("400.1", "answer")))
                .isTrue();
        assertNoResidue("R9");
        assertThat(stateOf("R9")).containsEntry("state", "COMPLETED");
    }

    @Test
    void 승인_실행이_소실되면_새_승인_전까지_실행하지_않고_복구_대상으로_돌아간다() throws Exception {
        makeUnknown("R10");
        recovery.reprocess("R10");
        Msg re = readNew().get(0);
        claimOk(re);
        Thread.sleep(LEASE_MS + 150); // 워커가 죽어 임대가 만료됐다고 본다

        // 재전달: 결과표 4'행 → DEAD(manual_attempt_lost), 자동으로 다시 돌리지 않는다
        assertThat(store.claim(re.request())).isEqualTo(new Settled(Reason.DEAD));
        assertThat(stateOf("R10")).containsEntry("state", "DEAD").containsEntry("stage", "manual_attempt_lost");
        assertThat(listed(RedisProcessingStateStore.DLQ_KEY, "R10")).isTrue();
        assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, "R10")).isFalse();
        // 또 재전달돼도 실행하지 않는다(4'행이 ACK로 본문을 지웠으니 NoInput이 정상이다)
        assertThat(store.claim(re.request())).isNotInstanceOf(Claimed.class);
        assertThat(stateOf("R10")).containsEntry("state", "DEAD");

        // 새 승인은 다음 gen을 쓰고 다시 한 번만 실행된다
        assertThat(recovery.reprocess("R10").detail()).isEqualTo("2");
        Claimed again = claimOk(readNew().get(0));
        assertThat(again.gen()).isEqualTo(2);
        assertThat(again.manualRun()).isTrue();
    }

    @Test
    void 자동_실행_창을_넘긴_건은_차단되고_수동_승인은_한_번_실행된다() {
        long old = System.currentTimeMillis() - 25 * 60 * 60 * 1000L;
        Msg m = deliver("R11", 0, old, Map.of());
        // 창 초과 자동 차단: 선점하지 않고 DLQ로 보낸다
        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.EXPIRED));
        // 재전달된 옛 메시지도 실행되지 않는다
        Msg redelivered = deliver("R11", 0, old, Map.of());
        assertThat(store.claim(redelivered.request())).isNotInstanceOf(Claimed.class);

        assertThat(recovery.reprocess("R11").ok()).isTrue();
        Msg approved = readNew().get(0);

        Claimed c = claimOk(approved);
        assertThat(c.manualRun()).isTrue();
        // 승인은 소비됐다 — 같은 gen이 다시 와도 실행하지 않는다
        assertThat(store.claim(approved.request())).isInstanceOf(ClaimOutcome.Busy.class);
    }

    @Test
    void 창을_넘긴_건이_실제로_창_초과로_DEAD가_된_뒤_승인_실행도_가능하다() {
        long old = System.currentTimeMillis() - 25 * 60 * 60 * 1000L;
        Msg m = deliver("R12", 0, old, Map.of());
        assertThat(store.claim(m.request())).isEqualTo(new Settled(Reason.EXPIRED));
        assertThat(stateOf("R12")).containsEntry("state", "DEAD").containsEntry("stage", "window_expired");

        assertThat(recovery.reprocess("R12").ok()).isTrue();

        Claimed c = claimOk(readNew().get(0));
        assertThat(c.manualRun()).isTrue();
        assertThat(c.gen()).isEqualTo(1);
    }

    @Test
    void 창을_넘긴_승인_실행이_소실돼도_새_승인_없이는_다시_돌지_않는다() throws Exception {
        long old = System.currentTimeMillis() - 25 * 60 * 60 * 1000L;
        Msg m = deliver("R13", 0, old, Map.of());
        store.claim(m.request()); // EXPIRED → DEAD
        recovery.reprocess("R13");
        Msg re = readNew().get(0);
        claimOk(re);
        Thread.sleep(LEASE_MS + 150);

        assertThat(store.claim(re.request())).isEqualTo(new Settled(Reason.DEAD));
        assertThat(stateOf("R13")).containsEntry("stage", "manual_attempt_lost");
    }

    @Test
    void 보존본이_없으면_reprocess는_거절되고_아무것도_바꾸지_않는다() {
        makeUnknown("R14");
        redis.delete(RedisProcessingStateStore.preservedKey("R14"));

        assertThat(recovery.reprocess("R14").status()).isEqualTo("NO_PRESERVED");
        assertThat(stateOf("R14")).containsEntry("state", "UNKNOWN").containsEntry("gen", "0");
        assertThat(readNew()).isEmpty();
    }

    @Test
    void 이미_완료된_건은_reprocess할_수_없다() {
        makeUnknown("R15");
        recovery.resolveCompleted("R15", "1.1");

        assertThat(recovery.reprocess("R15").status()).isEqualTo("BAD_STATE");
        assertThat(readNew()).isEmpty();
    }

    @Test
    void reprocess는_어느_쓰기_뒤에_끊겨도_다시_실행하면_승인_실행이_하나만_돈다() {
        for (int failAfter = 1; failAfter <= 8; failAfter++) {
            reset();
            // 1~4는 UNKNOWN 출발, 5~8은 DLQ(DEAD) 출발 — 두 출발 모두 같은 쓰기 지점을 지난다
            boolean dead = failAfter > 4;
            int injectAt = dead ? failAfter - 4 : failAfter;
            String id = "RP" + failAfter;
            if (dead) {
                makeDead(id, System.currentTimeMillis());
            } else {
                makeUnknown(id);
            }

            recovery.injectFailureAfter(injectAt);
            try {
                recovery.reprocess(id);
            } catch (RuntimeException expected) {
                // 주입한 실패
            }
            recovery.injectFailureAfter(0);

            RecoveryOutcome again = recovery.reprocess(id);
            if (again.ok()) {
                assertThat(again.detail()).as("gen은 한 번만 오른다").isEqualTo("1");
            } else {
                // 실질 작업(상태·XADD·목록 제거)이 이미 끝난 뒤 끊긴 경우(DEAD 출발에서 빈 복구 목록 ZREM만 남음).
                // 재실행은 이미 승인된 건이라 거절되는 것이 맞고, 상태는 그대로 하나의 승인만 담고 있다.
                assertThat(again.status()).as("fail_after=%d", failAfter).isEqualTo("BAD_STATE");
                assertThat(listed(RedisProcessingStateStore.DLQ_KEY, id)).isFalse();
                assertThat(listed(RedisProcessingStateStore.RECOVERY_KEY, id)).isFalse();
            }
            assertThat(stateOf(id)).containsEntry("gen", "1").containsEntry("manual_gen", "1");

            // 중복 투입이 있어도(끊긴 뒤 재실행) 선점 결과표가 하나만 실행시킨다.
            List<Msg> msgs = readNew();
            assertThat(msgs).isNotEmpty();
            int claimed = 0;
            for (Msg msg : msgs) {
                ClaimOutcome o = store.claim(msg.request());
                if (o instanceof Claimed) {
                    claimed++;
                }
            }
            assertThat(claimed).as("fail_after=%d 승인 실행 수", failAfter).isEqualTo(1);
        }
    }
}
