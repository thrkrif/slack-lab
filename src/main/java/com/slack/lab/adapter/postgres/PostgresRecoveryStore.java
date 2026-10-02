package com.slack.lab.adapter.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.StateProperties;
import com.slack.lab.core.model.RecoveryOutcome;
import com.slack.lab.core.port.RecoveryStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * recovery CLI와 결과 불명 자동 조회가 쓰는 Postgres 구현(ADR-9, M22). 자동 재발신 경로는 없다. 입력 본문은 목록에 노출하지
 * 않는다(PRD §7). 연산마다 한 트랜잭션이고 이벤트 단위 어드바이저리 락으로 워커의 전이와 직렬화된다.
 *
 * <p>{@code reprocess}는 재시도 예약과 같은 경로를 탄다: 상태를 {@code RETRY_WAIT(gen+1, manual_gen)}으로 올리고 보존
 * 입력을 재시도 목록에 즉시 도래로 옮기면, 릴레이가 큐에 다시 넣는다. 승인은 그 세대의 첫 선점에서 소비된다(B11).
 */
public class PostgresRecoveryStore implements RecoveryStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StateProperties state;
    private final ObjectMapper mapper;

    public PostgresRecoveryStore(DataSource dataSource, StateProperties state, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.tx.setTimeout(30);
        this.state = state;
        this.mapper = mapper;
    }

    private void lock(String eventId) {
        jdbc.execute("SET LOCAL lock_timeout = '10s'");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, eventId);
    }

    private long now() {
        Long v = jdbc.queryForObject("SELECT (extract(epoch from clock_timestamp()) * 1000)::bigint", Long.class);
        return v == null ? System.currentTimeMillis() : v;
    }

    @Override
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        jdbc.query("""
                SELECT p.event_id, p.list_name, p.reason, p.preserved_at, s.state, s.stage, s.gen
                FROM preserved_input p LEFT JOIN processing_state s ON s.event_id = p.event_id
                WHERE p.list_name IN ('dlq', 'recovery')
                ORDER BY CASE p.list_name WHEN 'recovery' THEN 0 ELSE 1 END, p.preserved_at""",
                rs -> {
                    Map<String, String> st = new LinkedHashMap<>();
                    put(st, "state", rs.getString("state"));
                    put(st, "stage", rs.getString("stage"));
                    put(st, "gen", rs.getObject("gen") == null ? null : String.valueOf(rs.getLong("gen")));
                    out.add(new Entry(rs.getString("list_name"), rs.getString("event_id"), rs.getLong("preserved_at"), st,
                            rs.getString("reason")));
                });
        return out;
    }

    @Override
    public Map<String, String> stateOf(String eventId) {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.query("""
                SELECT state, stage, gen, kind, slack_ts, channel, thread_ts, retries, first_received_at
                FROM processing_state WHERE event_id = ?""",
                rs -> {
                    for (String col : List.of("state", "stage", "gen", "kind", "slack_ts", "channel", "thread_ts",
                            "retries", "first_received_at")) {
                        Object v = rs.getObject(col);
                        put(out, col, v == null ? null : v.toString());
                    }
                }, eventId);
        return out;
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null && !v.isEmpty()) {
            m.put(k, v);
        }
    }

    @Override
    public Optional<ThreadRef> threadRef(String eventId) {
        String channel = "";
        String threadTs = "";
        String ts = "";
        List<String> payloads = jdbc.queryForList("SELECT payload FROM preserved_input WHERE event_id = ?", String.class,
                eventId);
        if (!payloads.isEmpty()) {
            try {
                JsonNode n = mapper.readTree(payloads.get(0));
                channel = n.path("channel").asText("");
                threadTs = n.path("thread_ts").asText("");
                ts = n.path("ts").asText("");
            } catch (Exception ignored) {
                // 깨진 입력이면 상태의 위치를 쓴다
            }
        }
        if (channel.isBlank()) {
            Map<String, String> st = stateOf(eventId);
            channel = st.getOrDefault("channel", "");
            if (threadTs.isBlank()) {
                threadTs = st.getOrDefault("thread_ts", "");
            }
        }
        // 답글은 thread_ts가 있으면 그 스레드에, 없으면 멘션 메시지(ts)를 루트로 달렸다(SlackMessageEvent.replyThreadTs).
        String root = threadTs.isBlank() ? ts : threadTs;
        if (channel.isBlank() || root.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ThreadRef(channel, root));
    }

    @Override
    public RecoveryOutcome resolveCompleted(String eventId, String slackTs) {
        return resolve(eventId, "COMPLETED", slackTs, "manual_resolved");
    }

    @Override
    public RecoveryOutcome resolveCompletedAutomatically(String eventId, String slackTs) {
        // 감사·실험 기록에서 사람의 조치와 섞이지 않게 단계를 구분한다.
        return resolve(eventId, "COMPLETED", slackTs, "auto_resolved");
    }

    @Override
    public RecoveryOutcome close(String eventId) {
        return resolve(eventId, "CLOSED", "", "manual_closed");
    }

    private RecoveryOutcome resolve(String eventId, String to, String slackTs, String stage) {
        return tx.execute(status -> {
            lock(eventId);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT state, slack_ts FROM processing_state WHERE event_id = ?", eventId);
            if (rows.isEmpty()) {
                return new RecoveryOutcome("NOT_FOUND", "");
            }
            String st = (String) rows.get(0).get("state");
            String existingTs = (String) rows.get(0).get("slack_ts");
            boolean rerun = to.equals(st);
            if (!rerun && !"UNKNOWN".equals(st) && !"DEAD".equals(st)) {
                return new RecoveryOutcome("BAD_STATE", st == null ? "" : st);
            }
            if (rerun && "COMPLETED".equals(to) && !Objects.equals(existingTs == null ? "" : existingTs, slackTs)) {
                return new RecoveryOutcome("CONFLICT", existingTs == null ? "" : existingTs);
            }
            long now = now();
            if (!rerun) {
                if ("COMPLETED".equals(to)) {
                    jdbc.update("UPDATE processing_state SET state = 'COMPLETED', stage = ?, slack_ts = ?, "
                            + "input = NULL, updated_at = ? WHERE event_id = ?", stage, slackTs, now, eventId);
                } else {
                    jdbc.update("UPDATE processing_state SET state = 'CLOSED', stage = ?, input = NULL, "
                            + "updated_at = ? WHERE event_id = ?", stage, now, eventId);
                }
            }
            // 사람이 명시적으로 끝낸 건이라 세대 비교 없이 보존 입력을 지운다(B18). 보존 기간(만료 시각)은 마지막에 건다 —
            // 중간에 끊겨도 같은 명령을 다시 실행하면 같은 결과로 끝난다(멱등).
            jdbc.update("DELETE FROM preserved_input WHERE event_id = ?", eventId);
            jdbc.update("UPDATE processing_state SET expires_at = ? WHERE event_id = ?",
                    now + Duration.ofDays(state.completedRetentionDays()).toMillis(), eventId);
            return new RecoveryOutcome("OK", "");
        });
    }

    @Override
    public RecoveryOutcome reprocess(String eventId) {
        return tx.execute(status -> {
            lock(eventId);
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT state, gen, manual_gen FROM processing_state WHERE event_id = ?", eventId);
            if (rows.isEmpty()) {
                return new RecoveryOutcome("NOT_FOUND", "");
            }
            String st = (String) rows.get(0).get("state");
            long gen = ((Number) rows.get(0).get("gen")).longValue();
            Object manualGen = rows.get(0).get("manual_gen");
            List<Map<String, Object>> preserved = jdbc.queryForList(
                    "SELECT payload, list_name FROM preserved_input WHERE event_id = ?", eventId);
            // 같은 명령을 다시 실행해도 안전하다: 이미 승인돼 재투입을 기다리는 건은 같은 세대로 OK를 돌려준다.
            if ("RETRY_WAIT".equals(st) && manualGen != null && !preserved.isEmpty()
                    && "retry".equals(preserved.get(0).get("list_name"))) {
                return new RecoveryOutcome("OK", String.valueOf(gen));
            }
            if (!"UNKNOWN".equals(st) && !"DEAD".equals(st)) {
                return new RecoveryOutcome("BAD_STATE", st == null ? "" : st);
            }
            if (preserved.isEmpty()) {
                return new RecoveryOutcome("NO_PRESERVED", "");
            }
            long next = gen + 1;
            long now = now();
            String payload = PreservedInputJson.withGen(mapper, (String) preserved.get(0).get("payload"), next);
            // 상태가 먼저 올라가야 한다: 재투입 메시지의 세대가 상태보다 크면 이상(ANOMALY)으로 판정된다.
            jdbc.update("""
                    UPDATE processing_state SET state = 'RETRY_WAIT', gen = ?, retry_at = ?, manual_gen = ?,
                        stage = 'manual_reprocess', expires_at = NULL, updated_at = ? WHERE event_id = ?""",
                    next, now, next, now, eventId);
            jdbc.update("""
                    UPDATE preserved_input SET list_name = 'retry', reason = 'manual_reprocess', gen = ?, due_at = ?,
                        payload = ?, relayed_at = NULL, preserved_at = ? WHERE event_id = ?""",
                    next, now, payload, now, eventId);
            return new RecoveryOutcome("OK", String.valueOf(next));
        });
    }
}
