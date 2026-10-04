package com.slack.lab.adapter.postgres;

import com.slack.lab.core.port.IndexLock;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Postgres 세션 advisory lock으로 색인의 동시 실행을 막는다. 잠금은 연결에 묶이므로 핸들이 연결 하나를 쥐고 있다가 닫을 때
 * 풀기(풀에 돌려주는 것만으로는 세션 잠금이 남는다). 프로세스가 죽으면 연결이 끊겨 서버가 잠금을 푼다.
 */
public class PostgresIndexLock implements IndexLock {

    private static final Logger log = LoggerFactory.getLogger(PostgresIndexLock.class);
    // 다른 advisory lock과 겹치지 않는 고정 키.
    static final long KEY = 0x524147494E444558L; // "RAGINDEX"

    private final DataSource ds;

    public PostgresIndexLock(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public Optional<Handle> tryAcquire() {
        Connection c = null;
        try {
            c = ds.getConnection();
            boolean got;
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
                ps.setLong(1, KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    got = rs.getBoolean(1);
                }
            }
            if (!got) {
                c.close();
                return Optional.empty();
            }
            return Optional.of(new PgHandle(c));
        } catch (SQLException e) {
            closeQuietly(c);
            throw new IllegalStateException("색인 잠금을 얻지 못했다: sqlstate=" + e.getSQLState(), e);
        }
    }

    private static final class PgHandle implements Handle {
        private final Connection c;
        private boolean closed;

        PgHandle(Connection c) {
            this.c = c;
        }

        @Override
        public synchronized boolean isHeld() {
            if (closed) {
                return false;
            }
            // 연결이 살아 있고 이 세션이 실제로 그 advisory lock을 쥐고 있는지 서버에 묻는다.
            try (PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' "
                    + "AND pid = pg_backend_pid() AND granted AND ((classid::bigint << 32) | objid::bigint) = ?")) {
                ps.setLong(1, KEY);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            } catch (SQLException e) {
                return false; // 연결이 끊겼다
            }
        }

        @Override
        public synchronized void close() {
            if (!closed) {
                closed = true;
                release(c);
            }
        }
    }

    private static void release(Connection c) {
        try {
            try (PreparedStatement ps = c.prepareStatement("SELECT pg_advisory_unlock(?)")) {
                ps.setLong(1, KEY);
                ps.execute();
            }
            c.close();
        } catch (SQLException e) {
            // 풀기에 실패하면 풀로 돌려보내지 않고 물리 연결을 끊어 서버가 잠금을 풀게 한다.
            log.warn("색인 잠금 해제 실패 — 연결을 끊는다 sqlstate={}", e.getSQLState());
            try {
                c.abort(Runnable::run);
            } catch (SQLException ignored) {
                // 이미 닫혔다
            }
        }
    }

    private static void closeQuietly(Connection c) {
        if (c != null) {
            try {
                c.close();
            } catch (SQLException ignored) {
                // 이미 닫혔다
            }
        }
    }
}
