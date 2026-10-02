package com.slack.lab.adapter.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.StateProperties;
import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;
import com.slack.lab.core.port.ProcessingStateStore;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Postgres 작업 상태 저장소(ADR-9, M20). Redis 구현(state.lua)의 선점 결과표·임대·세대·보존을 같은 의미로 옮긴다.
 *
 * <p>Redis 구현은 Lua가 롤백하지 않아 "검증 → 멱등 보존 → 상태 → ACK" 순서로 부분 실패를 견뎠다. 여기서는 한 연산이
 * 한 트랜잭션이므로 중간 실패는 전부 롤백된다 — 그 순서 규율이 필요 없고, 대신 연산마다 이벤트 단위 어드바이저리 락으로
 * 같은 이벤트의 동시 전이를 직렬화한다(행이 아직 없는 첫 선점 경합도 같은 락으로 막는다). "지금"은 DB 시계다.
 *
 * <p>ACK는 이 저장소의 일이 아니다(포트 계약). 입력은 선점 때 받은 {@code ClaimRequest.input}을 상태 행에 보관했다가
 * 결과 불명·DLQ·재시도 전이에서 보존한다. 아직 운영에 배선하지 않는다 — 재시도 재투입은 M21(큐 어댑터)과 함께 한다.
 */
public class PostgresProcessingStateStore implements ProcessingStateStore {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final StateProperties state;
    private final ObjectMapper mapper;
    // 테스트 전용: 트랜잭션 안에서 N번째 SQL 뒤에 예외를 내 전체 롤백을 검증한다. 운영 빈에서는 항상 0이다.
    // 스레드마다 따로 센다(여러 워커 스레드가 같은 빈을 동시에 쓴다).
    private volatile int failAfterStatements;
    private final ThreadLocal<int[]> statements = ThreadLocal.withInitial(() -> new int[1]);

    public PostgresProcessingStateStore(DataSource dataSource, StateProperties state, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        // 이 설계는 READ COMMITTED에 기댄다: 어드바이저리 락을 얻은 뒤의 읽기가 최신 커밋을 봐야 한다. REPEATABLE READ 이상이면
        // 스냅샷이 락 대기 전에 잡혀 첫 선점 경합에서 낡은 데이터를 본다. 기본값에 의존하지 않고 명시한다.
        this.tx.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_READ_COMMITTED);
        // 한 연산이 오래 멈추면(네트워크 단절, idle in transaction) 같은 이벤트를 기다리는 호출이 연결을 쥔 채 쌓인다.
        this.tx.setTimeout(30);
        this.state = state;
        this.mapper = mapper;
    }

    void injectFailureAfterStatements(int n) {
        this.failAfterStatements = n;
        this.statements.get()[0] = 0;
    }

    // ---- 도우미

    /** 이벤트 단위 직렬화. 트랜잭션이 끝나면 풀린다. */
    private void lock(String eventId) {
        // 락 대기는 무한이 아니다. 풀이 고갈되면 다른 이벤트의 선점까지 멈춘다.
        jdbc.execute("SET LOCAL lock_timeout = '10s'");
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, eventId);
    }

    private long now() {
        Long v = jdbc.queryForObject("SELECT (extract(epoch from clock_timestamp()) * 1000)::bigint", Long.class);
        return v == null ? System.currentTimeMillis() : v;
    }

    private void write(String sql, Object... args) {
        jdbc.update(sql, args);
        int n = ++statements.get()[0];
        if (failAfterStatements > 0 && n >= failAfterStatements) {
            throw new IllegalStateException("INJECTED_FAILURE after statement " + n);
        }
    }

    private record Row(String state, String attemptId, long gen, long leaseUntil, long firstReceivedAt, String channel,
            String threadTs, boolean manualRun, Long manualGen, int retries, Long retryAt, String stage, String input) {}

    private Row load(String eventId) {
        List<Row> rows = jdbc.query("""
                SELECT state, attempt_id, gen, lease_until, first_received_at, channel, thread_ts, manual_run,
                       manual_gen, retries, retry_at, stage, input
                FROM processing_state WHERE event_id = ?""",
                (rs, i) -> new Row(rs.getString("state"), rs.getString("attempt_id"), rs.getLong("gen"),
                        rs.getLong("lease_until"), rs.getLong("first_received_at"), rs.getString("channel"),
                        rs.getString("thread_ts"), rs.getBoolean("manual_run"), rs.getObject("manual_gen", Long.class),
                        rs.getInt("retries"), rs.getObject("retry_at", Long.class), rs.getString("stage"),
                        rs.getString("input")),
                eventId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /** 멱등: 같은 입력을 다시 써도 결과가 같다. 한 이벤트는 한 목록에만 있다. */
    private void preserve(String eventId, String list, String reason, long gen, long dueAt, String payload, long now) {
        preserve(eventId, list, reason, gen, dueAt, payload, now, true);
    }

    /**
     * @param overwriteOtherList false면 이미 다른 목록에 보존된 이벤트는 건드리지 않는다. 상태보다 큰 세대의 이상 메시지(행 1)가
     *     재시도 예약·복구 대기 중인 정상 보존본을 덮어쓰면 그 이벤트가 목록에서 사라진다(Redis는 목록 멤버십이 따로여서
     *     남았지만 여기서는 한 이벤트가 한 행이다).
     */
    private void preserve(String eventId, String list, String reason, long gen, long dueAt, String payload, long now,
            boolean overwriteOtherList) {
        write("""
                INSERT INTO preserved_input (event_id, list_name, reason, gen, due_at, payload, preserved_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (event_id) DO UPDATE SET list_name = EXCLUDED.list_name, reason = EXCLUDED.reason,
                    gen = EXCLUDED.gen, due_at = EXCLUDED.due_at, payload = EXCLUDED.payload,
                    preserved_at = EXCLUDED.preserved_at
                WHERE ? OR preserved_input.list_name = EXCLUDED.list_name""",
                eventId, list, reason, gen, dueAt, payload, now, overwriteOtherList);
    }

    /**
     * 보존본이 지금 완료되는 세대보다 미래 것이 아닐 때만 지운다. 더 최근 세대가 남긴 보존본을 걷어내지 않기 위해서다
     * (state.lua {@code cleanup_preserved_if_same_or_past_gen}과 같은 이유).
     */
    private void cleanupPreserved(String eventId, long gen) {
        write("DELETE FROM preserved_input WHERE event_id = ? AND gen <= ?", eventId, gen);
    }

    private static String listFor(String st) {
        return "UNKNOWN".equals(st) ? "recovery" : "dlq";
    }

    // ---- 포트

    @Override
    public ClaimOutcome claim(ClaimRequest r) {
        return tx.execute(status -> {
            statements.get()[0] = 0;
            String id = r.eventId();
            lock(id);
            long now = now();
            Row row = load(id);
            boolean exists = row != null;
            long m = r.gen();
            long s = exists ? row.gen : -1;
            String st = exists ? row.state : null;
            boolean leaseValid = exists && row.leaseUntil > now;
            String payload = r.input() == null ? null
                    : PreservedInputJson.write(mapper, r.input(), m, r.receivedAtMs());

            // 1: 상태 gen보다 큰 메시지는 불변식 위반이다(gen은 항상 상태에 먼저 기록된 뒤 투입된다).
            if (exists && m > s) {
                if (payload == null) {
                    return new ClaimOutcome.NoInput();
                }
                preserve(id, "dlq", "anomaly_gen", m, now, payload, now, false);
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.ANOMALY);
            }
            // 2: 해결된 건. 사람이 닫은 CLOSED도 여기서 끝나 다시 보존되지 않는다.
            if ("COMPLETED".equals(st) || "CLOSED".equals(st)) {
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.DONE);
            }
            // 2'/2'': 같은 세대의 재전달만 다시 보존한다(멱등). 이전 세대는 보존 없이 끝난다.
            if ("UNKNOWN".equals(st) || "DEAD".equals(st)) {
                if (m == s) {
                    if (payload == null) {
                        return new ClaimOutcome.NoInput();
                    }
                    preserve(id, listFor(st), "recovered_" + st.toLowerCase(), m, now, payload, now);
                }
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.DONE);
            }
            // 3
            if (exists && m < s) {
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.STALE);
            }
            // 4: 발신했는지 알 수 없다. 재선점하지 않고 결과 불명으로 보낸다(24시간 판정보다 우선).
            if ("SENDING".equals(st) && !leaseValid) {
                if (payload == null) {
                    return new ClaimOutcome.NoInput();
                }
                preserve(id, "recovery", "sending_lease_expired", m, now, payload, now);
                write("UPDATE processing_state SET state = 'UNKNOWN', stage = 'sending_lease_expired', "
                        + "expires_at = NULL, input = NULL, updated_at = ? WHERE event_id = ?", now, id);
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.UNKNOWN);
            }
            // 4': 사람이 승인한 1회 실행이 소실됐다. 새 승인 없이는 다시 돌리지 않는다.
            if ("PROCESSING".equals(st) && !leaseValid && row.manualRun) {
                if (payload == null) {
                    return new ClaimOutcome.NoInput();
                }
                preserve(id, "dlq", "manual_attempt_lost", m, now, payload, now);
                write("UPDATE processing_state SET state = 'DEAD', stage = 'manual_attempt_lost', "
                        + "expires_at = NULL, input = NULL, updated_at = ? WHERE event_id = ?", now, id);
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.DEAD);
            }
            // 5
            if (("PROCESSING".equals(st) || "SENDING".equals(st)) && leaseValid) {
                return new ClaimOutcome.Busy();
            }
            // 6
            if ("RETRY_WAIT".equals(st) && row.retryAt != null && row.retryAt > now) {
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.STALE);
            }

            // 여기부터 실행 가능: 없음 / 임대 만료 PROCESSING / 도래한 RETRY_WAIT
            long first = exists ? Math.min(r.receivedAtMs(), row.firstReceivedAt) : r.receivedAtMs();
            long gen = exists ? s : m;
            boolean manual = exists && row.manualGen != null && row.manualGen == m;
            long windowMs = Duration.ofHours(state.executionWindowHours()).toMillis();

            // 7: 자동 실행 허용 기간(최초 수신 후 24시간) 초과
            if (now - first > windowMs && !manual) {
                if (payload == null) {
                    return new ClaimOutcome.NoInput();
                }
                preserve(id, "dlq", "window_expired", m, now, payload, now);
                upsertState(id, "DEAD", null, gen, 0, first, r, false, row, "window_expired", null, now);
                return new ClaimOutcome.Settled(ClaimOutcome.Reason.EXPIRED);
            }

            // 8: 선점. 입력 없이 실행하면 결과 불명·DLQ·재시도 전이가 보존할 입력이 없어 거절되고, 임대 만료 후 재선점을
            // 24시간 창이 닫힐 때까지 되풀이한다. 입구에서 막는다.
            if (payload == null) {
                return new ClaimOutcome.NoInput();
            }
            // 승인은 여기서 소비된다. retries는 이 claim이 손대지 않는다(RETRY_WAIT이 남긴 값을 물려받는다).
            String attemptId = UUID.randomUUID().toString();
            upsertState(id, "PROCESSING", attemptId, gen, now + state.leaseMs(), first, r, manual, row, null, payload,
                    now);
            return new ClaimOutcome.Claimed(attemptId, gen, manual, exists ? row.retries : 0);
        });
    }

    /** 새 행이면 만들고 있으면 갱신한다. {@code manual}이면 승인(manual_gen)을 같은 문장에서 지운다. */
    private void upsertState(String id, String newState, String attemptId, long gen, long leaseUntil, long first,
            ClaimRequest r, boolean manual, Row existing, String stage, String input, long now) {
        write("""
                INSERT INTO processing_state (event_id, state, attempt_id, gen, lease_until, first_received_at, channel,
                    thread_ts, manual_run, stage, input, expires_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?)
                ON CONFLICT (event_id) DO UPDATE SET state = EXCLUDED.state,
                    attempt_id = COALESCE(EXCLUDED.attempt_id, processing_state.attempt_id), gen = EXCLUDED.gen,
                    lease_until = CASE WHEN EXCLUDED.attempt_id IS NULL THEN processing_state.lease_until
                                       ELSE EXCLUDED.lease_until END,
                    first_received_at = EXCLUDED.first_received_at, channel = EXCLUDED.channel,
                    thread_ts = EXCLUDED.thread_ts,
                    manual_run = CASE WHEN EXCLUDED.attempt_id IS NULL THEN processing_state.manual_run
                                      ELSE EXCLUDED.manual_run END,
                    manual_gen = CASE WHEN ? THEN NULL ELSE processing_state.manual_gen END,
                    stage = COALESCE(EXCLUDED.stage, processing_state.stage),
                    -- 선점이면 이번 입력으로 갱신하고, 종료(창 초과 DEAD)면 비운다: 본문은 보존 테이블에만 있어야 한다(B18).
                    input = EXCLUDED.input,
                    expires_at = NULL, updated_at = EXCLUDED.updated_at""",
                id, newState, attemptId, gen, leaseUntil, first, r.channel(), r.threadTs(), manual, stage, input, now,
                manual);
    }

    @Override
    public boolean markSending(String eventId, String attemptId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            statements.get()[0] = 0;
            lock(eventId);
            long now = now();
            Row row = load(eventId);
            if (row == null || !attemptId.equals(row.attemptId) || !"PROCESSING".equals(row.state)
                    || row.leaseUntil <= now) {
                return false;
            }
            write("UPDATE processing_state SET state = 'SENDING', updated_at = ? WHERE event_id = ?", now, eventId);
            return true;
        }));
    }

    @Override
    public boolean renew(String eventId, String attemptId) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            statements.get()[0] = 0;
            lock(eventId);
            long now = now();
            Row row = load(eventId);
            // 이미 만료된 임대는 되살리지 않는다(그 사이 다른 시도가 판정됐을 수 있다).
            if (row == null || !attemptId.equals(row.attemptId)
                    || !("PROCESSING".equals(row.state) || "SENDING".equals(row.state)) || row.leaseUntil <= now) {
                return false;
            }
            write("UPDATE processing_state SET lease_until = ?, updated_at = ? WHERE event_id = ?",
                    now + state.leaseMs(), now, eventId);
            return true;
        }));
    }

    @Override
    public boolean finalizeAttempt(String eventId, String attemptId, String deliveryToken, Finalization f) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            statements.get()[0] = 0;
            lock(eventId);
            long now = now();
            Row row = load(eventId);
            if (row == null || !attemptId.equals(row.attemptId)) {
                return false;
            }
            String from = row.state;
            String to = f.state().name();
            // 늦은 완료: SENDING 임대 만료로 이미 UNKNOWN이 된 뒤 실제로는 성공했음이 확인된 경우.
            // 자가 재완료: COMPLETED 기록 뒤 호출자가 중단돼 같은 시도가 같은 인자로 다시 부르는 경우.
            boolean allowed = ("COMPLETED".equals(to) && ("SENDING".equals(from) || "UNKNOWN".equals(from)
                    || "COMPLETED".equals(from)))
                    || ("UNKNOWN".equals(to) && "SENDING".equals(from))
                    || ("DEAD".equals(to) && ("PROCESSING".equals(from) || "SENDING".equals(from)));
            if (!allowed) {
                return false;
            }
            if (f.preserveTo() != Finalization.Destination.NONE) {
                if (row.input == null) {
                    return false; // 보존할 입력이 없으면 아무것도 쓰지 않고 거절한다
                }
                String list = f.preserveTo() == Finalization.Destination.RECOVERY ? "recovery" : "dlq";
                preserve(eventId, list, nz(f.stage()), row.gen, now, row.input, now);
            }
            Long expires = "COMPLETED".equals(to) ? now + Duration.ofDays(state.completedRetentionDays()).toMillis()
                    : null;
            write("""
                    UPDATE processing_state SET state = ?, stage = ?, kind = ?, slack_ts = ?, expires_at = ?,
                        input = NULL, updated_at = ? WHERE event_id = ?""",
                    to, nz(f.stage()), nz(f.kind()), nz(f.slackTs()), expires, now, eventId);
            if ("COMPLETED".equals(to)) {
                cleanupPreserved(eventId, row.gen);
            }
            return true;
        }));
    }

    @Override
    public boolean scheduleRetry(String eventId, String attemptId, String deliveryToken, long nextGen, long retryAtMs,
            int retries, String stage) {
        return Boolean.TRUE.equals(tx.execute(status -> {
            statements.get()[0] = 0;
            lock(eventId);
            long now = now();
            Row row = load(eventId);
            if (row == null || !attemptId.equals(row.attemptId)
                    || !("PROCESSING".equals(row.state) || "SENDING".equals(row.state)) || row.input == null) {
                return false;
            }
            // 재투입될 입력은 다음 세대 번호로 한 번에 쓴다(보존본과 상태의 세대가 어긋나는 순간을 만들지 않는다).
            preserve(eventId, "retry", "retry_scheduled", nextGen, retryAtMs,
                    PreservedInputJson.withGen(mapper, row.input, nextGen), now);
            write("""
                    UPDATE processing_state SET state = 'RETRY_WAIT', gen = ?, retry_at = ?, retries = ?, stage = ?,
                        expires_at = NULL, updated_at = ? WHERE event_id = ?""",
                    nextGen, retryAtMs, retries, nz(stage), now, eventId);
            return true;
        }));
    }

    // ---- 운영 보조 (M22에서 스케줄러·복구 CLI가 쓴다)

    /** 보존 기한이 지난 완료·닫힘 행을 지운다. 미해결 건(만료 시각 없음)은 지우지 않는다. 지운 행 수를 돌려준다. */
    public int purgeExpired() {
        Integer n = tx.execute(status -> jdbc.update(
                "DELETE FROM processing_state WHERE expires_at IS NOT NULL AND expires_at <= ?", now()));
        return n == null ? 0 : n;
    }

    /** 미해결 보존 건 수(목록별). 키는 {@code dlq}·{@code recovery}·{@code retry}. */
    public java.util.Map<String, Long> preservedCounts() {
        var out = new java.util.LinkedHashMap<String, Long>();
        jdbc.query("SELECT list_name, count(*) c FROM preserved_input GROUP BY list_name",
                rs -> {
                    out.put(rs.getString("list_name"), rs.getLong("c"));
                });
        return out;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
