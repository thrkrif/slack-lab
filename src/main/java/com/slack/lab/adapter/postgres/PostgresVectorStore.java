package com.slack.lab.adapter.postgres;

import com.slack.lab.core.model.DocumentChunk;
import com.slack.lab.core.model.DocumentHit;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SearchResult;
import com.slack.lab.core.port.VectorStore;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;

/**
 * pgvector 기반 {@link VectorStore}(M25). 컬럼은 차원 없는 {@code vector}이고(무인덱스 전수 검색) 세대마다 기록한 모델·차원을
 * 쓰기와 검색에서 검증한다. 문서 교체는 한 트랜잭션이라 중간에 죽어도 옛 조각과 새 조각이 섞이지 않는다.
 *
 * <p>전체 재색인은 {@code pending} 세대에 쓰다가 {@link #commitRebuild()}가 {@code live}를 한 번에 바꾼다. 상태 행 잠금이
 * 순서를 정한다: 문서 쓰기는 {@code FOR SHARE}, 세대 전환은 {@code FOR UPDATE}, 읽기(검색 포함)는 잠그지 않는다.
 *
 * <p>검색 기한은 호출 스레드가 쥔다. 커넥션 대기와 쿼리 실행은 별도 스레드에서 하고, 기한이 지나면 호출자가 쿼리를 취소하고
 * 포기한다 — HTTP 타임아웃만으로는 DB 쪽 대기를 막지 못하기 때문이다(PLAN M28 마감 계산).
 */
public class PostgresVectorStore implements VectorStore, AutoCloseable {

    private static final String QUERY_CANCELED = "57014";
    /** 동시에 기한을 쥐고 있을 수 있는 검색 수의 상한. 넘으면 기다리지 않고 실패로 돌려줘 스레드·연결이 쌓이지 않게 한다. */
    private static final int MAX_CONCURRENT_SEARCHES = 4;

    private final DataSource ds;
    private final ExecutorService searchPool = daemonPool("pg-vector-search", MAX_CONCURRENT_SEARCHES);
    // 취소는 네트워크 왕복이라 느릴 수 있다(pgjdbc 기본 취소 통신 제한 10초). 호출자가 기한에 반환하도록 따로 돌린다.
    private final ExecutorService cancelPool = daemonPool("pg-vector-cancel", MAX_CONCURRENT_SEARCHES);

    private static ExecutorService daemonPool(String name, int max) {
        ThreadFactory f = r -> {
            Thread t = new Thread(r, name);
            t.setDaemon(true);
            return t;
        };
        return new ThreadPoolExecutor(0, max, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), f);
    }

    public PostgresVectorStore(DataSource ds) {
        this.ds = ds;
    }

    @Override
    public void close() {
        searchPool.shutdownNow();
        cancelPool.shutdownNow();
    }

    // ---- 메타 ----

    @Override
    public PortResult<Optional<IndexMeta>> meta() {
        return snapshotRead(c -> {
            State s = readState(c, Lock.NONE);
            return Optional.ofNullable(s.live == null ? null : generation(c, s.live)).map(Generation::meta);
        });
    }

    @Override
    public PortResult<Void> initMeta(IndexMeta meta) {
        return tx(c -> {
            State s = readState(c, Lock.UPDATE);
            if (s.live != null) {
                IndexMeta existing = generation(c, s.live).meta();
                if (!existing.equals(meta)) {
                    throw new Rejected(ErrorInfo.of(ErrorCode.INDEX_META_MISMATCH,
                            existing.modelId() + "/" + existing.dimension() + " != " + meta.modelId() + "/" + meta.dimension()));
                }
                return null;
            }
            long g = createGeneration(c, meta);
            update(c, "UPDATE rag_index_state SET live_generation = ? WHERE id = 1", g);
            return null;
        });
    }

    @Override
    public PortResult<Map<String, String>> indexedHashes() {
        return snapshotRead(c -> {
            Long target = target(readState(c, Lock.NONE));
            Map<String, String> out = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT document_id, content_hash FROM rag_document WHERE generation = ?")) {
                ps.setLong(1, target);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.put(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return out;
        });
    }

    // ---- 쓰기 ----

    @Override
    public PortResult<Void> replaceDocument(String documentId, String title, String contentHash,
            List<DocumentChunk> chunks) {
        return tx(c -> {
            long g = target(readState(c, Lock.SHARE));
            int dim = generation(c, g).dimension;
            // 삭제 전에 검증한다. 차원이 틀린 청크가 하나라도 있으면 옛 문서를 건드리지 않고 거부한다.
            for (DocumentChunk chunk : chunks) {
                float[] e = chunk.embedding();
                if (e.length != dim) {
                    throw new Rejected(ErrorInfo.of(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                            "chunk=" + chunk.index() + " dim=" + e.length + " expected=" + dim));
                }
                literal(e); // 비유한 값(NaN·Infinity)도 여기서 거부
            }
            update(c, "DELETE FROM rag_document WHERE generation = ? AND document_id = ?", g, documentId);
            update(c, "INSERT INTO rag_document (generation, document_id, title, content_hash) VALUES (?, ?, ?, ?)",
                    g, documentId, title, contentHash);
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO rag_chunk "
                    + "(generation, document_id, chunk_index, text, embedding) VALUES (?, ?, ?, ?, ?::vector)")) {
                for (DocumentChunk chunk : chunks) {
                    ps.setLong(1, g);
                    ps.setString(2, documentId);
                    ps.setInt(3, chunk.index());
                    ps.setString(4, chunk.text());
                    ps.setString(5, literal(chunk.embedding()));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            return null;
        });
    }

    @Override
    public PortResult<Void> deleteDocuments(Collection<String> documentIds) {
        return tx(c -> {
            long g = target(readState(c, Lock.SHARE));
            for (String id : documentIds) {
                update(c, "DELETE FROM rag_document WHERE generation = ? AND document_id = ?", g, id);
            }
            return null;
        });
    }

    // ---- 전체 재색인 ----

    @Override
    public PortResult<Void> beginRebuild(IndexMeta newMeta) {
        return tx(c -> {
            State s = readState(c, Lock.UPDATE);
            if (s.live == null) {
                throw new Rejected(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "index_not_initialized"));
            }
            if (s.pending != null) {
                throw new Rejected(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "rebuild_in_progress"));
            }
            long g = createGeneration(c, newMeta);
            update(c, "UPDATE rag_index_state SET pending_generation = ? WHERE id = 1", g);
            return null;
        });
    }

    @Override
    public PortResult<Void> commitRebuild() {
        return tx(c -> {
            State s = readState(c, Lock.UPDATE);
            if (s.pending == null) {
                throw new Rejected(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "no_rebuild_in_progress"));
            }
            long old = s.live;
            update(c, "UPDATE rag_index_state SET live_generation = pending_generation, pending_generation = NULL "
                    + "WHERE id = 1");
            update(c, "DELETE FROM rag_generation WHERE generation = ?", old); // 문서·청크는 cascade로 함께 지운다
            return null;
        });
    }

    @Override
    public PortResult<Void> abortRebuild() {
        return tx(c -> {
            State s = readState(c, Lock.UPDATE);
            if (s.pending != null) {
                update(c, "UPDATE rag_index_state SET pending_generation = NULL WHERE id = 1");
                update(c, "DELETE FROM rag_generation WHERE generation = ?", s.pending);
            }
            return null;
        });
    }

    // ---- 검색 ----

    @Override
    public SearchResult search(float[] query, int topK, long remainingMs) {
        long t0 = System.nanoTime();
        if (remainingMs <= 0) {
            return new SearchResult.TimedOut(0);
        }
        Abandon abandon = new Abandon();
        Future<SearchResult> future;
        try {
            future = searchPool.submit(() -> doSearch(query, topK, remainingMs, t0, abandon));
        } catch (RejectedExecutionException e) {
            return new SearchResult.Failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "search_saturated"), elapsedMs(t0));
        }
        try {
            return future.get(remainingMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            abandon(future, abandon);
            return new SearchResult.TimedOut(elapsedMs(t0));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            abandon(future, abandon);
            return new SearchResult.Failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "interrupted"), elapsedMs(t0));
        } catch (ExecutionException e) {
            return new SearchResult.Failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, e.getCause()), elapsedMs(t0));
        }
    }

    /** 호출자가 포기했는지와 지금 서버에서 실행 중인 문장. 포기 후에는 새 문장을 시작하지 않는다. */
    private static final class Abandon {
        final AtomicBoolean abandoned = new AtomicBoolean();
        final AtomicReference<Statement> running = new AtomicReference<>();
    }

    private SearchResult doSearch(float[] query, int topK, long remainingMs, long t0, Abandon abandon) {
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            c.setAutoCommit(false);
            c.setReadOnly(true);
            if (abandon.abandoned.get()) {
                return new SearchResult.TimedOut(elapsedMs(t0));
            }
            // 문장마다 적용되는 서버 쪽 상한이다. 연결 대기를 뺀 남은 시간으로 잡아, 호출자가 놓친 취소가 있어도 서버가 끊는다.
            try (Statement st = c.createStatement()) {
                st.execute("SET LOCAL statement_timeout = " + Math.max(1, remainingMs - elapsedMs(t0)));
            }
            State s = readState(c, Lock.NONE);
            if (s.live == null) {
                return new SearchResult.Success(List.of(), elapsedMs(t0));
            }
            Generation g = generation(c, s.live);
            if (abandon.abandoned.get()) {
                return new SearchResult.TimedOut(elapsedMs(t0));
            }
            if (query.length != g.dimension) {
                return new SearchResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                        "query=" + query.length + " index=" + g.dimension), elapsedMs(t0));
            }
            String q = literal(query);
            // <=>는 코사인 거리다. 임계값이 저장소 척도에 묶이지 않게 유사도(1 - 거리)로 돌려준다.
            try (PreparedStatement ps = c.prepareStatement("SELECT d.document_id, d.title, k.text, "
                    + "1 - (k.embedding <=> ?::vector) AS score FROM rag_chunk k JOIN rag_document d "
                    + "ON d.generation = k.generation AND d.document_id = k.document_id WHERE k.generation = ? "
                    + "ORDER BY k.embedding <=> ?::vector LIMIT ?")) {
                abandon.running.set(ps);
                if (abandon.abandoned.get()) { // 등록 직전에 포기됐으면 실행하지 않는다(취소가 실행 전에는 무시될 수 있다)
                    return new SearchResult.TimedOut(elapsedMs(t0));
                }
                ps.setString(1, q);
                ps.setLong(2, s.live);
                ps.setString(3, q);
                ps.setInt(4, topK);
                List<DocumentHit> hits = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        hits.add(new DocumentHit(rs.getString(1), rs.getString(2), rs.getString(3), rs.getDouble(4)));
                    }
                }
                return new SearchResult.Success(hits, elapsedMs(t0));
            }
        } catch (SQLException e) {
            if (QUERY_CANCELED.equals(e.getSQLState())) {
                return new SearchResult.TimedOut(elapsedMs(t0));
            }
            return new SearchResult.Failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, sqlDetail(e)), elapsedMs(t0));
        }
    }

    private void abandon(Future<SearchResult> future, Abandon abandon) {
        abandon.abandoned.set(true);
        Statement st = abandon.running.get();
        if (st != null) {
            try {
                cancelPool.execute(() -> {
                    try {
                        st.cancel(); // 서버 쪽 쿼리를 끊는다. 호출자는 이 완료를 기다리지 않는다
                    } catch (SQLException ignored) {
                        // 이미 끝났거나 연결이 닫혔다
                    }
                });
            } catch (RejectedExecutionException ignored) {
                // 취소 풀이 가득이면 서버의 statement_timeout이 끊는다
            }
        }
        future.cancel(true);
    }

    // ---- 내부 ----

    private record State(Long live, Long pending) {}

    private record Generation(String modelId, int dimension) {
        IndexMeta meta() {
            return new IndexMeta(modelId, dimension);
        }
    }

    /** 호출 규칙 위반처럼 롤백하고 정해진 오류로 돌려줄 사유. */
    private static final class Rejected extends RuntimeException {
        final ErrorInfo error;

        Rejected(ErrorInfo error) {
            super(error.text(), null, false, false);
            this.error = error;
        }
    }

    private interface Work<T> {
        T run(Connection c) throws SQLException;
    }

    private <T> PortResult<T> tx(Work<T> work) {
        return tx(Connection.TRANSACTION_READ_COMMITTED, work);
    }

    /**
     * 읽기 전용 조회는 한 스냅샷에서 상태→세대→문서를 읽는다. 읽는 도중 {@link #commitRebuild()}가 이전 세대를 지워도
     * 일부만 보이거나 조회가 어긋나지 않는다.
     */
    private <T> PortResult<T> snapshotRead(Work<T> work) {
        return tx(Connection.TRANSACTION_REPEATABLE_READ, work);
    }

    private <T> PortResult<T> tx(int isolation, Work<T> work) {
        try (Connection c = ds.getConnection()) {
            c.setTransactionIsolation(isolation);
            c.setAutoCommit(false);
            try {
                T value = work.run(c);
                c.commit();
                return PortResult.ok(value);
            } catch (Rejected e) {
                c.rollback();
                return PortResult.failed(e.error);
            } catch (SQLException | RuntimeException e) {
                c.rollback();
                throw e;
            }
        } catch (SQLException e) {
            return PortResult.failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, sqlDetail(e)));
        } catch (RuntimeException e) {
            return PortResult.failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, e));
        }
    }

    private enum Lock {
        NONE(""), SHARE(" FOR SHARE"), UPDATE(" FOR UPDATE");

        final String sql;

        Lock(String sql) {
            this.sql = sql;
        }
    }

    private static State readState(Connection c, Lock lockMode) throws SQLException {
        String lock = lockMode.sql;
        try (Statement st = c.createStatement();
                ResultSet rs = st.executeQuery(
                        "SELECT live_generation, pending_generation FROM rag_index_state WHERE id = 1" + lock)) {
            rs.next();
            long live = rs.getLong(1);
            Long liveV = rs.wasNull() ? null : live;
            long pending = rs.getLong(2);
            return new State(liveV, rs.wasNull() ? null : pending);
        }
    }

    /** 쓰기 대상: 재색인 중이면 대기 세대, 아니면 현재 세대. 색인이 초기화되지 않았으면 거부한다. */
    private static long target(State s) {
        Long t = s.pending != null ? s.pending : s.live;
        if (t == null) {
            throw new Rejected(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "index_not_initialized"));
        }
        return t;
    }

    private static Generation generation(Connection c, long generation) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT model_id, dimension FROM rag_generation WHERE generation = ?")) {
            ps.setLong(1, generation);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return new Generation(rs.getString(1), rs.getInt(2));
            }
        }
    }

    private static long createGeneration(Connection c, IndexMeta meta) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO rag_generation (model_id, dimension, created_at) VALUES (?, ?, ?) RETURNING generation")) {
            ps.setString(1, meta.modelId());
            ps.setInt(2, meta.dimension());
            ps.setLong(3, System.currentTimeMillis());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    private static void update(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private static String literal(float[] v) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < v.length; i++) {
            if (!Float.isFinite(v[i])) {
                throw new Rejected(ErrorInfo.of(ErrorCode.EMBEDDING_RESPONSE_INVALID, "non_finite_value"));
            }
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private static long elapsedMs(long t0) {
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static String sqlDetail(SQLException e) {
        return "sqlstate=" + e.getSQLState();
    }
}
