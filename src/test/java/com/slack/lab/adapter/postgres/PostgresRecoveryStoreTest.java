package com.slack.lab.adapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.StateProperties;
import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimOutcome.Claimed;
import com.slack.lab.core.model.ClaimOutcome.Reason;
import com.slack.lab.core.model.ClaimOutcome.Settled;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.RecoveryStore;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.time.Duration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 복구 CLI의 Postgres 구현(M22). 자동 재발신 경로가 없고, 해결된 건은 본문이 남지 않으며(B18), 재처리 승인은 한 번만 소비된다(B11). */
@Testcontainers
class PostgresRecoveryStoreTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:16-alpine");

    static final StateProperties STATE = new StateProperties(400, 100, 7, 24);
    static final long DAY_MS = Duration.ofDays(1).toMillis();
    static final ObjectMapper MAPPER = new ObjectMapper();

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    PostgresProcessingStateStore store;
    PostgresRecoveryStore recovery;

    @BeforeAll
    static void connect() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(PG.getJdbcUrl());
        c.setUsername(PG.getUsername());
        c.setPassword(PG.getPassword());
        c.setMaximumPoolSize(8);
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
        store = new PostgresProcessingStateStore(ds, STATE, MAPPER);
        recovery = new PostgresRecoveryStore(ds, STATE, MAPPER);
    }

    // --- 도우미

    static SlackMessageEvent event(String id, String threadTs) {
        return new SlackMessageEvent(id, "C-test", "U1", "질문 본문", "100.1", threadTs, null, null, null);
    }

    ClaimRequest req(String id, long gen, long receivedAt) {
        return new ClaimRequest(id, "t", gen, receivedAt, "C-test", "100.1", event(id, null));
    }

    String stateOf(String id) {
        return jdbc.queryForList("SELECT state FROM processing_state WHERE event_id = ?", String.class, id).stream()
                .findFirst().orElse(null);
    }

    boolean preserved(String id) {
        return !jdbc.queryForList("SELECT 1 FROM preserved_input WHERE event_id = ?", id).isEmpty();
    }

    void makeUnknown(String id) {
        Claimed c = (Claimed) store.claim(req(id, 0, System.currentTimeMillis()));
        store.markSending(id, c.attemptId());
        store.finalizeAttempt(id, c.attemptId(), "t", Finalization.unknown("answer", "answer_send:read_timeout"));
    }

    void makeDead(String id) {
        Claimed c = (Claimed) store.claim(req(id, 0, System.currentTimeMillis()));
        store.finalizeAttempt(id, c.attemptId(), "t", Finalization.dead("answer", "answer_send:invalid_auth"));
    }

    // --- list · stateOf · threadRef

    @Test
    void 목록은_결과_불명을_먼저_DLQ를_다음에_보여주고_본문은_담지_않는다() {
        makeDead("R2");
        makeUnknown("R1");

        var entries = recovery.list();

        assertThat(entries).extracting(RecoveryStore.Entry::eventId).containsExactly("R1", "R2");
        assertThat(entries.get(0).list()).isEqualTo("recovery");
        assertThat(entries.get(0).state()).containsEntry("state", "UNKNOWN").containsEntry("gen", "0");
        assertThat(entries.get(1).list()).isEqualTo("dlq");
        assertThat(entries.toString()).doesNotContain("질문 본문");
    }

    @Test
    void 재시도_예약_중인_건은_미해결_목록에_나오지_않는다() {
        Claimed c = (Claimed) store.claim(req("R3", 0, System.currentTimeMillis()));
        store.scheduleRetry("R3", c.attemptId(), "t", 1, System.currentTimeMillis() + 60_000, 1, "x");

        assertThat(recovery.list()).isEmpty();
    }

    @Test
    void 스레드_위치는_thread_ts가_있으면_그것을_없으면_ts를_루트로_쓴다() {
        makeUnknown("R4");
        assertThat(recovery.threadRef("R4")).contains(new RecoveryStore.ThreadRef("C-test", "100.1"));

        Claimed c = (Claimed) store.claim(new ClaimRequest("R5", "t", 0, System.currentTimeMillis(), "C-test", "50.5",
                event("R5", "50.5")));
        store.markSending("R5", c.attemptId());
        store.finalizeAttempt("R5", c.attemptId(), "t", Finalization.unknown("answer", "x"));
        assertThat(recovery.threadRef("R5")).contains(new RecoveryStore.ThreadRef("C-test", "50.5"));
        assertThat(recovery.threadRef("없음")).isEmpty();
    }

    // --- resolve · close (B18)

    @Test
    void resolve_completed는_COMPLETED로_바꾸고_보존_입력을_지운다() {
        makeUnknown("R6");

        RecoveryStore.Entry before = recovery.list().get(0);
        var out = recovery.resolveCompleted("R6", "300.1");

        assertThat(before.eventId()).isEqualTo("R6");
        assertThat(out.ok()).isTrue();
        assertThat(stateOf("R6")).isEqualTo("COMPLETED");
        assertThat(jdbc.queryForObject("SELECT slack_ts FROM processing_state WHERE event_id='R6'", String.class))
                .isEqualTo("300.1");
        assertThat(preserved("R6")).isFalse();
        assertThat(jdbc.queryForObject("SELECT expires_at FROM processing_state WHERE event_id='R6'", Long.class))
                .isNotNull();
        assertThat(recovery.list()).isEmpty();
    }

    @Test
    void resolve_completed는_같은_ts로_멱등이고_다른_ts는_거절한다() {
        makeUnknown("R7");
        assertThat(recovery.resolveCompleted("R7", "300.1").ok()).isTrue();

        assertThat(recovery.resolveCompleted("R7", "300.1").ok()).isTrue();
        var conflict = recovery.resolveCompleted("R7", "999.9");
        assertThat(conflict.status()).isEqualTo("CONFLICT");
        assertThat(conflict.detail()).isEqualTo("300.1");
    }

    @Test
    void 처리_중이거나_없는_건은_거절한다() {
        store.claim(req("R8", 0, System.currentTimeMillis()));

        assertThat(recovery.resolveCompleted("R8", "1.1").status()).isEqualTo("BAD_STATE");
        assertThat(recovery.close("R8").status()).isEqualTo("BAD_STATE");
        assertThat(recovery.reprocess("R8").status()).isEqualTo("BAD_STATE");
        assertThat(recovery.close("없음").status()).isEqualTo("NOT_FOUND");
        assertThat(recovery.reprocess("없음").status()).isEqualTo("NOT_FOUND");
        assertThat(stateOf("R8")).isEqualTo("PROCESSING");
    }

    @Test
    void close는_CLOSED로_바꾸고_늦은_재전송이_와도_다시_보존하지_않는다() {
        makeDead("R9");

        assertThat(recovery.close("R9").ok()).isTrue();

        assertThat(stateOf("R9")).isEqualTo("CLOSED");
        assertThat(preserved("R9")).isFalse();
        assertThat(store.claim(req("R9", 0, System.currentTimeMillis()))).isEqualTo(new Settled(Reason.DONE));
        assertThat(preserved("R9")).isFalse();
    }

    // --- reprocess (B11)

    @Test
    void reprocess는_gen을_올려_승인을_기록하고_릴레이가_원래_수신_시각으로_다시_넣게_한다() {
        long received = System.currentTimeMillis() - 5_000;
        Claimed c = (Claimed) store.claim(req("P1", 0, received));
        store.markSending("P1", c.attemptId());
        store.finalizeAttempt("P1", c.attemptId(), "t", Finalization.unknown("answer", "x"));

        var out = recovery.reprocess("P1");

        assertThat(out.ok()).isTrue();
        assertThat(out.detail()).isEqualTo("1");
        assertThat(jdbc.queryForMap("SELECT state, gen, manual_gen, stage FROM processing_state WHERE event_id='P1'"))
                .containsEntry("state", "RETRY_WAIT").containsEntry("gen", 1L).containsEntry("manual_gen", 1L)
                .containsEntry("stage", "manual_reprocess");
        var due = store.pollDueRetries(10, 60_000);
        assertThat(due).hasSize(1);
        assertThat(due.get(0).gen()).isEqualTo(1);
        assertThat(due.get(0).receivedAtMs()).isEqualTo(received);
        assertThat(due.get(0).event().text()).isEqualTo("질문 본문");
        assertThat(recovery.list()).as("승인된 건은 미해결 목록에서 빠진다").isEmpty();
    }

    @Test
    void reprocess를_다시_실행해도_세대는_한_번만_오른다() {
        makeUnknown("P2");
        assertThat(recovery.reprocess("P2").detail()).isEqualTo("1");

        var again = recovery.reprocess("P2");

        assertThat(again.ok()).isTrue();
        assertThat(again.detail()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT gen FROM processing_state WHERE event_id='P2'", Long.class)).isEqualTo(1L);
    }

    @Test
    void 승인_실행은_첫_선점에서_소비되고_완료되면_보존본이_지워진다() {
        makeUnknown("P3");
        recovery.reprocess("P3");

        Claimed c = (Claimed) store.claim(req("P3", 1, System.currentTimeMillis()));

        assertThat(c.manualRun()).isTrue();
        assertThat(jdbc.queryForObject("SELECT manual_gen FROM processing_state WHERE event_id='P3'", Long.class))
                .isNull();
        store.markSending("P3", c.attemptId());
        store.finalizeAttempt("P3", c.attemptId(), "t", Finalization.completed("400.1", "answer"));
        assertThat(preserved("P3")).isFalse();
        assertThat(stateOf("P3")).isEqualTo("COMPLETED");
    }

    @Test
    void 승인_실행이_소실되면_24시간_이내라도_새_승인_전까지_복구_대상으로_돌아간다() {
        makeUnknown("P4");
        recovery.reprocess("P4");
        Claimed c = (Claimed) store.claim(req("P4", 1, System.currentTimeMillis()));
        jdbc.update("UPDATE processing_state SET lease_until = 0 WHERE event_id = 'P4'");

        assertThat(store.claim(req("P4", 1, System.currentTimeMillis()))).isEqualTo(new Settled(Reason.DEAD));

        assertThat(c.manualRun()).isTrue();
        assertThat(recovery.list()).extracting(RecoveryStore.Entry::eventId).containsExactly("P4");
        // 새 승인은 다음 세대를 쓴다
        assertThat(recovery.reprocess("P4").detail()).isEqualTo("2");
    }

    @Test
    void 자동_실행_창을_넘긴_건도_수동_승인으로_한_번_실행된다() {
        long old = System.currentTimeMillis() - DAY_MS - 60_000;
        assertThat(store.claim(req("P5", 0, old))).isEqualTo(new Settled(Reason.EXPIRED));
        assertThat(recovery.list()).extracting(RecoveryStore.Entry::eventId).containsExactly("P5");

        assertThat(recovery.reprocess("P5").ok()).isTrue();

        ClaimOutcome o = store.claim(req("P5", 1, old));
        assertThat(o).isInstanceOf(Claimed.class);
        assertThat(((Claimed) o).manualRun()).isTrue();
    }

    @Test
    void 보존본이_없으면_reprocess는_거절하고_아무것도_바꾸지_않는다() {
        makeUnknown("P6");
        jdbc.update("DELETE FROM preserved_input WHERE event_id = 'P6'");

        assertThat(recovery.reprocess("P6").status()).isEqualTo("NO_PRESERVED");
        assertThat(stateOf("P6")).isEqualTo("UNKNOWN");
    }

    @Test
    void 이미_완료된_건은_reprocess할_수_없다() {
        makeUnknown("P7");
        recovery.resolveCompleted("P7", "1.1");

        assertThat(recovery.reprocess("P7").status()).isEqualTo("BAD_STATE");
    }

    @Test
    void 일반_재시도_대기_건은_수동_재처리할_수_없다() {
        Claimed c = (Claimed) store.claim(req("Q1", 0, System.currentTimeMillis()));
        store.scheduleRetry("Q1", c.attemptId(), "t", 1, System.currentTimeMillis() + 60_000, 1, "x");

        assertThat(recovery.reprocess("Q1").status()).isEqualTo("BAD_STATE");
        assertThat(jdbc.queryForObject("SELECT manual_gen FROM processing_state WHERE event_id='Q1'", Long.class)).isNull();
    }

    @Test
    void 자동_조회가_완료한_건은_사람의_조치와_단계가_구분된다() {
        makeUnknown("Q2");
        makeUnknown("Q3");

        assertThat(recovery.resolveCompletedAutomatically("Q2", "5.5").ok()).isTrue();
        assertThat(recovery.resolveCompleted("Q3", "6.6").ok()).isTrue();

        assertThat(jdbc.queryForObject("SELECT stage FROM processing_state WHERE event_id='Q2'", String.class))
                .isEqualTo("auto_resolved");
        assertThat(jdbc.queryForObject("SELECT stage FROM processing_state WHERE event_id='Q3'", String.class))
                .isEqualTo("manual_resolved");
    }

    @Test
    void 깨진_재시도_입력으로_격리된_건은_복구_CLI로_닫을_수_있다() {
        Claimed c = (Claimed) store.claim(req("Q4", 0, System.currentTimeMillis()));
        store.scheduleRetry("Q4", c.attemptId(), "t", 1, System.currentTimeMillis() - 60_000, 1, "x");
        jdbc.update("UPDATE preserved_input SET payload = '깨진{' WHERE event_id = 'Q4'");
        store.pollDueRetries(10, 0); // 격리된다

        assertThat(recovery.list()).extracting(RecoveryStore.Entry::eventId).contains("Q4");
        assertThat(recovery.close("Q4").ok()).isTrue();
        assertThat(recovery.list()).isEmpty();
    }
}
