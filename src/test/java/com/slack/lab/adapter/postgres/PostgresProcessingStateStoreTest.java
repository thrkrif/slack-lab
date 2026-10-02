package com.slack.lab.adapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.StateProperties;
import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimOutcome.Claimed;
import com.slack.lab.core.model.ClaimOutcome.Reason;
import com.slack.lab.core.model.ClaimOutcome.Settled;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;
import com.slack.lab.core.model.SlackMessageEvent;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Postgres 작업 상태 저장소(M20). Redis 구현의 선점 결과표 각 행과 우선순위 조합, 임대, 보존, 수동 승인(B11)을 같은
 * 의미로 검증한다. Redis와 달리 한 연산이 한 트랜잭션이라 "부분 실패 후 재실행" 대신 "실패하면 전부 롤백"을 검증한다.
 */
@Testcontainers
class PostgresProcessingStateStoreTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static final long LEASE_MS = 400;
    static final StateProperties STATE = new StateProperties(LEASE_MS, 100, 7, 24);
    static final long DAY_MS = Duration.ofDays(1).toMillis();

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    PostgresProcessingStateStore store;

    @BeforeAll
    static void connect() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(PG.getJdbcUrl());
        c.setUsername(PG.getUsername());
        c.setPassword(PG.getPassword());
        c.setMaximumPoolSize(12);
        ds = new HikariDataSource(c);
        PostgresMigrations.migrate(ds);
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void close() {
        ds.close();
    }

    @BeforeEach
    void reset() {
        jdbc.update("TRUNCATE processing_state, preserved_input");
        store = new PostgresProcessingStateStore(ds, STATE, new ObjectMapper());
    }

    // --- 도우미

    static SlackMessageEvent event(String id) {
        return new SlackMessageEvent(id, "C-test", "U1", "질문 본문", "1.0", null, null, null, null);
    }

    ClaimRequest req(String id, long gen, long receivedAt) {
        return new ClaimRequest(id, "tok-" + id + "-" + gen, gen, receivedAt, "C-test", "1.0", event(id));
    }

    ClaimRequest req(String id) {
        return req(id, 0, System.currentTimeMillis());
    }

    Claimed claimOk(ClaimRequest r) {
        ClaimOutcome o = store.claim(r);
        assertThat(o).isInstanceOf(Claimed.class);
        return (Claimed) o;
    }

    Map<String, Object> stateOf(String id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT * FROM processing_state WHERE event_id = ?", id);
        return rows.isEmpty() ? Map.of() : rows.get(0);
    }

    String listOf(String id) {
        List<String> l = jdbc.queryForList("SELECT list_name FROM preserved_input WHERE event_id = ?", String.class, id);
        return l.isEmpty() ? null : l.get(0);
    }

    boolean preserved(String id) {
        return listOf(id) != null;
    }

    static void sleep(long ms) throws InterruptedException {
        Thread.sleep(ms);
    }

    /** 임대를 결정적으로 만료시킨다. JVM sleep과 컨테이너(DB) 시계가 어긋나도 흔들리지 않는다. */
    void expireLease(String id) {
        jdbc.update("UPDATE processing_state SET lease_until = 0 WHERE event_id = ?", id);
    }

    Claimed toSending(String id) {
        Claimed c = claimOk(req(id));
        assertThat(store.markSending(id, c.attemptId())).isTrue();
        return c;
    }

    // --- 8행: 새 이벤트 선점과 정상 완료

    @Test
    void 새_이벤트는_선점되고_완료_기록으로_끝난다() {
        Claimed c = claimOk(req("E1"));
        assertThat(stateOf("E1")).containsEntry("state", "PROCESSING").containsEntry("gen", 0L)
                .containsEntry("manual_run", false);
        assertThat(c.gen()).isZero();
        assertThat(c.retries()).isZero();
        assertThat(c.manualRun()).isFalse();

        assertThat(store.markSending("E1", c.attemptId())).isTrue();
        assertThat(store.finalizeAttempt("E1", c.attemptId(), "tok", Finalization.completed("300.1", "answer"))).isTrue();

        assertThat(stateOf("E1")).containsEntry("state", "COMPLETED").containsEntry("slack_ts", "300.1")
                .containsEntry("kind", "answer");
        assertThat(stateOf("E1").get("expires_at")).isNotNull();
    }

    @Test
    void 동시에_같은_이벤트를_선점하면_하나만_이긴다() throws Exception {
        int n = 10;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<ClaimOutcome>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            Callable<ClaimOutcome> task = () -> {
                start.await();
                return store.claim(req("E2"));
            };
            futures.add(pool.submit(task));
        }
        start.countDown();
        int claimed = 0;
        int busy = 0;
        for (Future<ClaimOutcome> f : futures) {
            ClaimOutcome o = f.get();
            if (o instanceof Claimed) {
                claimed++;
            } else if (o instanceof ClaimOutcome.Busy) {
                busy++;
            }
        }
        pool.shutdownNow();

        assertThat(claimed).isEqualTo(1);
        assertThat(busy).isEqualTo(n - 1);
    }

    @Test
    void 소유자가_아니면_어떤_전이도_거절된다() {
        Claimed c = claimOk(req("E3"));

        assertThat(store.markSending("E3", "다른-시도")).isFalse();
        assertThat(store.renew("E3", "다른-시도")).isFalse();
        assertThat(store.finalizeAttempt("E3", "다른-시도", "t", Finalization.dead("answer", "x"))).isFalse();
        assertThat(store.scheduleRetry("E3", "다른-시도", "t", 1, System.currentTimeMillis(), 1, "x")).isFalse();
        assertThat(store.markSending("없는-이벤트", c.attemptId())).isFalse();
        assertThat(stateOf("E3")).containsEntry("state", "PROCESSING");
        assertThat(preserved("E3")).isFalse();
    }

    // --- 2행: 해결된 건

    @Test
    void 완료된_이벤트의_재전달은_실행하지_않고_종료로_판정한다() {
        Claimed c = toSending("E4");
        store.finalizeAttempt("E4", c.attemptId(), "t", Finalization.completed("1.1", "answer"));

        assertThat(store.claim(req("E4"))).isEqualTo(new Settled(Reason.DONE));
        assertThat(stateOf("E4")).containsEntry("state", "COMPLETED");
        assertThat(preserved("E4")).isFalse();
    }

    @Test
    void 사람이_닫은_건에_늦은_재전송이_와도_다시_보존하지_않는다() {
        Claimed c = claimOk(req("E5"));
        store.finalizeAttempt("E5", c.attemptId(), "t", Finalization.dead("answer", "x"));
        jdbc.update("UPDATE processing_state SET state = 'CLOSED' WHERE event_id = 'E5'");
        jdbc.update("DELETE FROM preserved_input WHERE event_id = 'E5'");

        assertThat(store.claim(req("E5"))).isEqualTo(new Settled(Reason.DONE));
        assertThat(preserved("E5")).isFalse();
    }

    // --- 2'행: UNKNOWN/DEAD

    @Test
    void 같은_세대_UNKNOWN_재전달은_멱등하게_다시_보존하고_이전_세대는_보존하지_않는다() {
        Claimed c = toSending("E6");
        store.finalizeAttempt("E6", c.attemptId(), "t", Finalization.unknown("answer", "answer_send:x"));
        assertThat(listOf("E6")).isEqualTo("recovery");
        jdbc.update("DELETE FROM preserved_input WHERE event_id = 'E6'");

        assertThat(store.claim(req("E6", 0, System.currentTimeMillis()))).isEqualTo(new Settled(Reason.DONE));
        assertThat(listOf("E6")).as("같은 세대 재전달은 보존을 채운다").isEqualTo("recovery");

        jdbc.update("UPDATE processing_state SET gen = 2 WHERE event_id = 'E6'");
        jdbc.update("DELETE FROM preserved_input WHERE event_id = 'E6'");
        assertThat(store.claim(req("E6", 1, System.currentTimeMillis()))).isEqualTo(new Settled(Reason.DONE));
        assertThat(preserved("E6")).as("이전 세대는 다시 보존하지 않는다").isFalse();
    }

    // --- 3·6행: STALE

    @Test
    void 이전_세대와_예약된_재시도보다_이른_메시지는_STALE이다() {
        Claimed c = claimOk(req("E7"));
        long retryAt = System.currentTimeMillis() + 60_000;
        assertThat(store.scheduleRetry("E7", c.attemptId(), "t", 1, retryAt, 1, "llm_timeout")).isTrue();

        assertThat(store.claim(req("E7", 0, System.currentTimeMillis()))).isEqualTo(new Settled(Reason.STALE));
        assertThat(store.claim(req("E7", 1, System.currentTimeMillis()))).as("예약 시각 전").isEqualTo(
                new Settled(Reason.STALE));
    }

    // --- 4행: 발신 중 임대 만료

    @Test
    void 발신_중_임대가_만료되면_UNKNOWN으로_가고_입력을_복구_목록에_보존한다() throws Exception {
        toSending("E8");
        expireLease("E8");

        assertThat(store.claim(req("E8"))).isEqualTo(new Settled(Reason.UNKNOWN));
        assertThat(stateOf("E8")).containsEntry("state", "UNKNOWN").containsEntry("stage", "sending_lease_expired");
        assertThat(listOf("E8")).isEqualTo("recovery");
        assertThat(stateOf("E8").get("expires_at")).as("미해결 건은 만료되지 않는다").isNull();
    }

    @Test
    void 늦게_도착한_같은_시도의_성공은_UNKNOWN을_COMPLETED로_바꾸고_보존본을_지운다() throws Exception {
        Claimed c = toSending("E9");
        expireLease("E9");
        store.claim(req("E9"));
        assertThat(stateOf("E9")).containsEntry("state", "UNKNOWN");

        assertThat(store.finalizeAttempt("E9", c.attemptId(), "t", Finalization.completed("9.9", "answer"))).isTrue();

        assertThat(stateOf("E9")).containsEntry("state", "COMPLETED").containsEntry("slack_ts", "9.9");
        assertThat(preserved("E9")).isFalse();
    }

    // --- 5행·8행: 임대

    @Test
    void 처리_중_임대가_만료되면_새_시도가_재선점하고_이전_소유자는_발신할_수_없다() throws Exception {
        Claimed first = claimOk(req("E10"));
        sleep(LEASE_MS * 3); // 실제 시간 경과로 만료되는 경로는 이 테스트 하나로 확인한다(여유 있게)

        Claimed second = claimOk(req("E10"));

        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
        assertThat(store.markSending("E10", first.attemptId())).isFalse();
        assertThat(store.markSending("E10", second.attemptId())).isTrue();
    }

    @Test
    void 임대가_유효하면_갱신되고_BUSY로_지켜진다() throws Exception {
        Claimed c = claimOk(req("E11"));
        sleep(LEASE_MS / 2);
        assertThat(store.renew("E11", c.attemptId())).isTrue();
        sleep(LEASE_MS / 2 + 100);

        assertThat(store.claim(req("E11"))).isInstanceOf(ClaimOutcome.Busy.class);
    }

    @Test
    void 이미_만료된_임대는_갱신으로_되살리지_못한다() throws Exception {
        Claimed c = claimOk(req("E12"));
        expireLease("E12");

        assertThat(store.renew("E12", c.attemptId())).isFalse();
        assertThat(store.markSending("E12", c.attemptId())).isFalse();
    }

    // --- 7행: 24시간

    @Test
    void 최초_수신_후_24시간이_지나면_자동_실행하지_않고_DLQ로_보낸다() {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;

        assertThat(store.claim(req("E13", 0, old))).isEqualTo(new Settled(Reason.EXPIRED));
        assertThat(stateOf("E13")).containsEntry("state", "DEAD").containsEntry("stage", "window_expired");
        assertThat(listOf("E13")).isEqualTo("dlq");
    }

    @Test
    void 우선순위_조합에서_24시간_판정은_실행_가능한_행에만_적용된다() throws Exception {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;

        // 만료 SENDING + 24시간 초과 → UNKNOWN(4행이 7행보다 먼저). 창 초과 건은 새로 선점할 수 없으므로
        // 정상 선점 뒤 최초 수신 시각을 과거로 돌려 상황을 만든다.
        Claimed p1 = claimOk(req("P1"));
        store.markSending("P1", p1.attemptId());
        jdbc.update("UPDATE processing_state SET first_received_at = ?, lease_until = 0 WHERE event_id = 'P1'", old);
        assertThat(store.claim(req("P1", 0, old))).isEqualTo(new Settled(Reason.UNKNOWN));

        // COMPLETED + 24시간 초과 → DONE
        Claimed p2 = toSending("P2");
        store.finalizeAttempt("P2", p2.attemptId(), "t", Finalization.completed("1.1", "answer"));
        jdbc.update("UPDATE processing_state SET first_received_at = ? WHERE event_id = 'P2'", old);
        assertThat(store.claim(req("P2", 0, old))).isEqualTo(new Settled(Reason.DONE));

        // 이전 세대 + 24시간 초과 → STALE(3행)
        Claimed p3 = claimOk(req("P3"));
        store.scheduleRetry("P3", p3.attemptId(), "t", 1, System.currentTimeMillis() + 60_000, 1, "x");
        jdbc.update("UPDATE processing_state SET first_received_at = ? WHERE event_id = 'P3'", old);
        assertThat(store.claim(req("P3", 0, old))).isEqualTo(new Settled(Reason.STALE));
    }

    // --- 수동 승인 (B11)

    @Test
    void 승인된_세대는_24시간이_지나도_한_번_실행되고_승인은_소비된다() {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;
        store.claim(req("E14", 0, old)); // EXPIRED → DEAD
        jdbc.update("UPDATE processing_state SET state = 'RETRY_WAIT', gen = 1, retry_at = 0, manual_gen = 1 "
                + "WHERE event_id = 'E14'");

        Claimed c = claimOk(req("E14", 1, old));

        assertThat(c.manualRun()).isTrue();
        assertThat(stateOf("E14").get("manual_gen")).isNull();
        assertThat(stateOf("E14")).containsEntry("manual_run", true);
    }

    @Test
    void 승인된_실행이_소실되면_새_승인_없이는_다시_돌리지_않는다() throws Exception {
        long recent = System.currentTimeMillis();
        store.claim(req("E15", 0, recent));
        jdbc.update("DELETE FROM processing_state WHERE event_id = 'E15'");
        jdbc.update("DELETE FROM preserved_input WHERE event_id = 'E15'");
        // 사람이 승인한 gen=1 실행을 만든다
        Claimed seed = claimOk(req("E15", 0, recent));
        store.finalizeAttempt("E15", seed.attemptId(), "t", Finalization.dead("answer", "x"));
        jdbc.update("UPDATE processing_state SET state = 'RETRY_WAIT', gen = 1, retry_at = 0, manual_gen = 1 "
                + "WHERE event_id = 'E15'");
        Claimed manual = claimOk(req("E15", 1, recent));
        assertThat(manual.manualRun()).isTrue();
        expireLease("E15");

        assertThat(store.claim(req("E15", 1, recent))).isEqualTo(new Settled(Reason.DEAD));
        assertThat(stateOf("E15")).containsEntry("state", "DEAD").containsEntry("stage", "manual_attempt_lost");
        assertThat(listOf("E15")).isEqualTo("dlq");
    }

    // --- 1행: ANOMALY

    @Test
    void 상태보다_큰_세대는_ANOMALY로_DLQ에_보존한다() {
        claimOk(req("E16"));

        assertThat(store.claim(req("E16", 5, System.currentTimeMillis()))).isEqualTo(new Settled(Reason.ANOMALY));
        assertThat(listOf("E16")).isEqualTo("dlq");
        assertThat(stateOf("E16")).containsEntry("state", "PROCESSING").containsEntry("gen", 0L);
    }

    @Test
    void 상태가_없으면_세대가_0보다_커도_1행을_건너뛰고_선점한다() {
        Claimed c = claimOk(req("E17", 3, System.currentTimeMillis()));
        assertThat(c.gen()).isEqualTo(3);
    }

    // --- 재시도 (M13)

    @Test
    void 재시도_예약은_RETRY_WAIT으로_기록하고_도래하면_다음_세대로_선점하며_횟수를_물려받는다() throws Exception {
        Claimed c = claimOk(req("E18"));
        long retryAt = System.currentTimeMillis() + 60_000;
        assertThat(store.scheduleRetry("E18", c.attemptId(), "t", 1, retryAt, 1, "llm_timeout")).isTrue();
        assertThat(stateOf("E18")).containsEntry("state", "RETRY_WAIT").containsEntry("gen", 1L)
                .containsEntry("retries", 1);
        assertThat(listOf("E18")).isEqualTo("retry");
        assertThat(jdbc.queryForObject("SELECT due_at FROM preserved_input WHERE event_id = 'E18'", Long.class))
                .isEqualTo(retryAt);

        jdbc.update("UPDATE processing_state SET retry_at = 0 WHERE event_id = 'E18'"); // 도래시킨다
        Claimed again = claimOk(req("E18", 1, System.currentTimeMillis()));

        assertThat(again.gen()).isEqualTo(1);
        assertThat(again.retries()).isEqualTo(1);
        assertThat(again.manualRun()).isFalse();
    }

    @Test
    void 재시도_예약된_입력은_다음_세대_번호로_보존된다() {
        Claimed c = claimOk(req("E19"));
        store.scheduleRetry("E19", c.attemptId(), "t", 1, System.currentTimeMillis(), 1, "x");

        String payload = jdbc.queryForObject("SELECT payload FROM preserved_input WHERE event_id = 'E19'", String.class);
        assertThat(PreservedInputJson.readGen(new ObjectMapper(), payload)).isEqualTo(1);
        assertThat(PreservedInputJson.readEvent(new ObjectMapper(), payload).text()).isEqualTo("질문 본문");
    }

    @Test
    void 재시도_뒤_완료되면_재시도_보존본이_정리된다() throws Exception {
        Claimed c = claimOk(req("E20"));
        store.scheduleRetry("E20", c.attemptId(), "t", 1, System.currentTimeMillis() + 60_000, 1, "x");
        jdbc.update("UPDATE processing_state SET retry_at = 0 WHERE event_id = 'E20'");
        Claimed again = claimOk(req("E20", 1, System.currentTimeMillis()));
        store.markSending("E20", again.attemptId());

        store.finalizeAttempt("E20", again.attemptId(), "t", Finalization.completed("2.2", "answer"));

        assertThat(preserved("E20")).isFalse();
    }

    @Test
    void 정상_완료는_더_최근_세대가_남긴_보존본을_지우지_않는다() {
        Claimed c = toSending("E21");
        // 다른 경로가 더 미래 세대(gen=3)의 DLQ 보존본을 남겼다
        jdbc.update("INSERT INTO preserved_input VALUES ('E21','dlq','anomaly_gen',3,0,'{}',0)");

        store.finalizeAttempt("E21", c.attemptId(), "t", Finalization.completed("1.1", "answer"));

        assertThat(listOf("E21")).isEqualTo("dlq");
    }

    // --- 입력 보존 계약

    @Test
    void 보존이_필요한데_입력이_없으면_NoInput이고_아무것도_쓰지_않는다() throws Exception {
        toSending("E22");
        expireLease("E22");

        ClaimOutcome o = store.claim(new ClaimRequest("E22", "t", 0, System.currentTimeMillis(), "C", "1.0"));

        assertThat(o).isInstanceOf(ClaimOutcome.NoInput.class);
        assertThat(stateOf("E22")).containsEntry("state", "SENDING");
        assertThat(preserved("E22")).isFalse();
    }

    @Test
    void 입력_없이는_선점하지_않는다() {
        ClaimOutcome o = store.claim(new ClaimRequest("E23", "t", 0, System.currentTimeMillis(), "C", "1.0"));

        assertThat(o).isInstanceOf(ClaimOutcome.NoInput.class);
        assertThat(stateOf("E23")).isEmpty();
        assertThat(preserved("E23")).isFalse();
    }

    @Test
    void 허용되지_않는_전이는_거절된다() {
        Claimed c = claimOk(req("E24"));
        // PROCESSING에서 바로 COMPLETED·UNKNOWN은 불가(발신 게이트를 거쳐야 한다)
        assertThat(store.finalizeAttempt("E24", c.attemptId(), "t", Finalization.completed("1", "answer"))).isFalse();
        assertThat(store.finalizeAttempt("E24", c.attemptId(), "t", Finalization.unknown("answer", "x"))).isFalse();
        // DEAD는 가능
        assertThat(store.finalizeAttempt("E24", c.attemptId(), "t", Finalization.dead("answer", "x"))).isTrue();
        assertThat(listOf("E24")).isEqualTo("dlq");
    }

    @Test
    void 자가_재완료는_멱등이다() {
        Claimed c = toSending("E25");
        assertThat(store.finalizeAttempt("E25", c.attemptId(), "t", Finalization.completed("1.1", "answer"))).isTrue();
        assertThat(store.finalizeAttempt("E25", c.attemptId(), "t", Finalization.completed("1.1", "answer"))).isTrue();
        assertThat(stateOf("E25")).containsEntry("state", "COMPLETED");
    }

    // --- 보존 기간

    @Test
    void 보존_기한이_지난_완료_건만_삭제하고_미해결_건은_남긴다() {
        Claimed done = toSending("D1");
        store.finalizeAttempt("D1", done.attemptId(), "t", Finalization.completed("1.1", "answer"));
        Claimed unknown = toSending("D2");
        store.finalizeAttempt("D2", unknown.attemptId(), "t", Finalization.unknown("answer", "x"));
        jdbc.update("UPDATE processing_state SET expires_at = 1 WHERE event_id = 'D1'");

        assertThat(store.purgeExpired()).isEqualTo(1);

        assertThat(stateOf("D1")).isEmpty();
        assertThat(stateOf("D2")).containsEntry("state", "UNKNOWN");
        assertThat(store.preservedCounts()).containsEntry("recovery", 1L);
    }

    // --- 원자성: 실패하면 전부 롤백

    @Test
    void 연산_중간에_실패하면_상태와_보존이_모두_원래대로다() {
        Claimed c = toSending("A1");
        // finalize(UNKNOWN)는 보존 INSERT 뒤 상태 UPDATE가 이어진다 — 첫 SQL 뒤에 실패를 주입한다
        store.injectFailureAfterStatements(1);
        assertThatThrownBy(() -> store.finalizeAttempt("A1", c.attemptId(), "t", Finalization.unknown("answer", "x")))
                .hasMessageContaining("INJECTED_FAILURE");
        store.injectFailureAfterStatements(0);

        assertThat(stateOf("A1")).containsEntry("state", "SENDING");
        assertThat(preserved("A1")).isFalse();
        // 같은 호출을 다시 하면 정상 종료된다
        assertThat(store.finalizeAttempt("A1", c.attemptId(), "t", Finalization.unknown("answer", "x"))).isTrue();
        assertThat(listOf("A1")).isEqualTo("recovery");
    }

    @Test
    void 선점_중간에_실패해도_부분_기록이_남지_않는다() throws Exception {
        toSending("A2");
        expireLease("A2");
        store.injectFailureAfterStatements(1); // 보존 INSERT 뒤, UNKNOWN 전환 전
        assertThatThrownBy(() -> store.claim(req("A2"))).hasMessageContaining("INJECTED_FAILURE");
        store.injectFailureAfterStatements(0);

        assertThat(stateOf("A2")).containsEntry("state", "SENDING");
        assertThat(preserved("A2")).isFalse();
        assertThat(store.claim(req("A2"))).isEqualTo(new Settled(Reason.UNKNOWN));
    }

    // --- 선점 지연 측정 (EXPERIMENT-LOG에 기록)

    @Test
    void 선점_지연을_측정한다() {
        int n = 200;
        long[] claimMs = new long[n];
        for (int i = 0; i < n; i++) {
            long t0 = System.nanoTime();
            store.claim(req("L" + i));
            claimMs[i] = (System.nanoTime() - t0) / 1_000_000;
        }
        java.util.Arrays.sort(claimMs);
        long p50 = claimMs[n / 2 - 1];
        long p95 = claimMs[(int) Math.ceil(0.95 * n) - 1];
        System.out.printf("POSTGRES_CLAIM_LATENCY_MS n=%d p50=%d p95=%d max=%d%n", n, p50, p95, claimMs[n - 1]);
        // 수신 경로 밖(워커)이라 엄격한 한도는 아니다. 터무니없이 느려지면 알린다.
        assertThat(p95).isLessThan(200);
    }

    // --- 리뷰 대응: B18(종료 뒤 본문 없음), 2'의 DEAD 경로, B11 불일치, 이상 메시지, 실제 시간 경과

    Object inputOf(String id) {
        return jdbc.queryForList("SELECT input FROM processing_state WHERE event_id = ?", id).get(0).get("input");
    }

    @Test
    void 종료된_건의_입력_본문은_상태_행에_남지_않는다() throws Exception {
        // COMPLETED
        Claimed c1 = toSending("B1");
        assertThat(inputOf("B1")).as("처리 중에는 입력을 들고 있다").isNotNull();
        store.finalizeAttempt("B1", c1.attemptId(), "t", Finalization.completed("1.1", "answer"));
        assertThat(inputOf("B1")).isNull();
        // UNKNOWN(finalize)
        Claimed c2 = toSending("B2");
        store.finalizeAttempt("B2", c2.attemptId(), "t", Finalization.unknown("answer", "x"));
        assertThat(inputOf("B2")).isNull();
        assertThat(preserved("B2")).as("본문은 보존 테이블에만 있다").isTrue();
        // DEAD(finalize)
        Claimed c3 = claimOk(req("B3"));
        store.finalizeAttempt("B3", c3.attemptId(), "t", Finalization.dead("answer", "x"));
        assertThat(inputOf("B3")).isNull();
        // UNKNOWN(임대 만료, 행 4)
        toSending("B4");
        expireLease("B4");
        store.claim(req("B4"));
        assertThat(inputOf("B4")).isNull();
        // DEAD(창 초과, 행 7)
        store.claim(req("B5", 0, System.currentTimeMillis() - DAY_MS - 60_000));
        assertThat(inputOf("B5")).isNull();
        // DEAD(승인 실행 소실, 행 4')
        Claimed seed = claimOk(req("B6"));
        store.finalizeAttempt("B6", seed.attemptId(), "t", Finalization.dead("answer", "x"));
        jdbc.update("UPDATE processing_state SET state = 'RETRY_WAIT', gen = 1, retry_at = 0, manual_gen = 1 "
                + "WHERE event_id = 'B6'");
        claimOk(req("B6", 1, System.currentTimeMillis()));
        expireLease("B6");
        store.claim(req("B6", 1, System.currentTimeMillis()));
        assertThat(inputOf("B6")).isNull();
        // 재시도 예약 중에는 보존 테이블이 입력을 들고 상태 행은 비어 있어도 된다 — 다시 선점되면 채워진다
        Claimed c7 = claimOk(req("B7"));
        store.scheduleRetry("B7", c7.attemptId(), "t", 1, System.currentTimeMillis() + 60_000, 1, "x");
        assertThat(preserved("B7")).isTrue();
    }

    @Test
    void 같은_세대_DEAD_재전달은_DLQ에_다시_보존한다() {
        Claimed c = claimOk(req("R1"));
        store.finalizeAttempt("R1", c.attemptId(), "t", Finalization.dead("answer", "x"));
        jdbc.update("DELETE FROM preserved_input WHERE event_id = 'R1'");

        assertThat(store.claim(req("R1"))).isEqualTo(new Settled(Reason.DONE));

        assertThat(listOf("R1")).isEqualTo("dlq");
    }

    @Test
    void 승인_세대와_다른_메시지는_수동_실행으로_보지_않는다() {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;
        store.claim(req("R2", 0, old)); // EXPIRED → DEAD
        // 승인은 gen=2에 묶여 있는데 gen=1 메시지가 온다(조건이 같지 않으면 24시간 창이 그대로 적용돼야 한다)
        jdbc.update("UPDATE processing_state SET state = 'RETRY_WAIT', gen = 1, retry_at = 0, manual_gen = 2 "
                + "WHERE event_id = 'R2'");

        assertThat(store.claim(req("R2", 1, old))).isEqualTo(new Settled(Reason.EXPIRED));
        assertThat(stateOf("R2").get("manual_gen")).as("승인은 소비되지 않았다").isEqualTo(2L);
    }

    @Test
    void 승인_실행이_24시간_뒤에_소실돼도_새_승인_없이는_다시_돌리지_않는다() {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;
        store.claim(req("R3", 0, old));
        jdbc.update("UPDATE processing_state SET state = 'RETRY_WAIT', gen = 1, retry_at = 0, manual_gen = 1 "
                + "WHERE event_id = 'R3'");
        assertThat(claimOk(req("R3", 1, old)).manualRun()).isTrue();
        expireLease("R3");

        assertThat(store.claim(req("R3", 1, old))).isEqualTo(new Settled(Reason.DEAD));
        assertThat(stateOf("R3")).containsEntry("stage", "manual_attempt_lost");
    }

    @Test
    void 상태보다_큰_세대의_이상_메시지는_재시도_예약_보존본을_덮어쓰지_않는다() {
        Claimed c = claimOk(req("R4"));
        store.scheduleRetry("R4", c.attemptId(), "t", 1, System.currentTimeMillis() + 60_000, 1, "x");

        assertThat(store.claim(req("R4", 5, System.currentTimeMillis()))).isEqualTo(new Settled(Reason.ANOMALY));

        assertThat(listOf("R4")).as("재시도 목록에서 사라지면 재투입할 주체가 없다").isEqualTo("retry");
    }

    @Test
    void 발신_중_임대가_실제_시간_경과로_만료돼도_UNKNOWN이_된다() throws Exception {
        toSending("R5");
        sleep(LEASE_MS * 3); // expireLease가 가리지 못하는 임대 비교 자체를 한 번은 실제 시간으로 확인한다

        assertThat(store.claim(req("R5"))).isEqualTo(new Settled(Reason.UNKNOWN));
    }
}
