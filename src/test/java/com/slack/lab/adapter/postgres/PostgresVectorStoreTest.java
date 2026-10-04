package com.slack.lab.adapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.TestImages;
import com.slack.lab.core.model.DocumentChunk;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SearchResult;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * pgvector 어댑터(M25). 문서 단위 교체의 원자성, 메타·차원 검증, 전체 재색인(대기 세대), 검색 기한과 쿼리 취소를 실제
 * Postgres에서 확인한다. 임베딩은 손으로 만든 3차원 벡터라 순서를 예측할 수 있다.
 */
@Testcontainers
class PostgresVectorStoreTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(TestImages.POSTGRES);

    static final IndexMeta META3 = new IndexMeta("test-model", 3);

    static HikariDataSource ds;
    static JdbcTemplate jdbc;
    PostgresVectorStore store;

    @BeforeAll
    static void connect() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(PG.getJdbcUrl());
        c.setUsername(PG.getUsername());
        c.setPassword(PG.getPassword());
        c.setMaximumPoolSize(6);
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
        jdbc.update("UPDATE rag_index_state SET live_generation = NULL, pending_generation = NULL");
        jdbc.update("DELETE FROM rag_generation");
        store = new PostgresVectorStore(ds);
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    static DocumentChunk chunk(int i, String text, float... v) {
        return new DocumentChunk(i, text, v);
    }

    static <T> T ok(PortResult<T> r) {
        assertThat(r).isInstanceOf(PortResult.Success.class);
        return ((PortResult.Success<T>) r).value();
    }

    static ErrorCode failedCode(PortResult<?> r) {
        assertThat(r).isInstanceOf(PortResult.Failed.class);
        return ((PortResult.Failed<?>) r).error().code();
    }

    static SearchResult.Success searched(SearchResult r) {
        assertThat(r).isInstanceOf(SearchResult.Success.class);
        return (SearchResult.Success) r;
    }

    @Test
    void 메타는_처음엔_없고_초기화하면_보이며_다른_값으로는_거부된다() {
        assertThat(ok(store.meta())).isEmpty();

        ok(store.initMeta(META3));
        assertThat(ok(store.meta())).contains(META3);
        ok(store.initMeta(META3)); // 같은 값은 멱등

        assertThat(failedCode(store.initMeta(new IndexMeta("other", 3)))).isEqualTo(ErrorCode.INDEX_META_MISMATCH);
        assertThat(failedCode(store.initMeta(new IndexMeta("test-model", 4)))).isEqualTo(ErrorCode.INDEX_META_MISMATCH);
        assertThat(ok(store.meta())).contains(META3);
    }

    @Test
    void 초기화_전에는_쓸_수_없고_검색은_빈_성공이다() {
        assertThat(failedCode(store.replaceDocument("d", "t", "h", List.of(chunk(0, "x", 1, 0, 0)))))
                .isEqualTo(ErrorCode.VECTOR_STORE_FAILED);
        assertThat(searched(store.search(new float[] {1, 0, 0}, 3, 2_000)).hits()).isEmpty();
    }

    @Test
    void 코사인_유사도_내림차순으로_돌려주고_점수는_유사도다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "문서 A", "ha", List.of(chunk(0, "a0", 1, 0, 0), chunk(1, "a1", 0, 1, 0))));
        ok(store.replaceDocument("B", "문서 B", "hb", List.of(chunk(0, "b0", 1, 1, 0))));

        var hits = searched(store.search(new float[] {1, 0, 0}, 2, 2_000)).hits();

        assertThat(hits).extracting("text").containsExactly("a0", "b0");
        assertThat(hits.get(0).score()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(1).score()).isCloseTo(Math.sqrt(0.5), org.assertj.core.data.Offset.offset(1e-6));
        assertThat(hits.get(0).documentId()).isEqualTo("A");
        assertThat(hits.get(0).title()).isEqualTo("문서 A");
    }

    @Test
    void 같은_문서를_다시_넣으면_옛_청크가_남지_않는다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h1", List.of(chunk(0, "old0", 1, 0, 0), chunk(1, "old1", 0, 1, 0))));
        ok(store.replaceDocument("A", "t", "h2", List.of(chunk(0, "new0", 0, 0, 1))));

        assertThat(jdbc.queryForList("SELECT text FROM rag_chunk ORDER BY chunk_index", String.class))
                .containsExactly("new0");
        assertThat(ok(store.indexedHashes())).containsEntry("A", "h2");
    }

    @Test
    void 차원이_틀린_청크가_섞이면_옛_문서를_건드리지_않고_거부한다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h1", List.of(chunk(0, "old", 1, 0, 0))));

        var r = store.replaceDocument("A", "t", "h2", List.of(chunk(0, "ok", 0, 1, 0), chunk(1, "bad", 1, 2)));

        assertThat(failedCode(r)).isEqualTo(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
        assertThat(jdbc.queryForList("SELECT text FROM rag_chunk", String.class)).containsExactly("old");
        assertThat(ok(store.indexedHashes())).containsEntry("A", "h1");
    }

    @Test
    void 삽입_중_실패하면_삭제까지_롤백되어_검색에서_문서가_사라지지_않는다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h1", List.of(chunk(0, "old", 1, 0, 0))));

        // 같은 청크 번호 두 개 → 기본키 위반으로 삽입이 중간에 실패한다
        var r = store.replaceDocument("A", "t", "h2", List.of(chunk(0, "x", 1, 0, 0), chunk(0, "y", 0, 1, 0)));

        assertThat(failedCode(r)).isEqualTo(ErrorCode.VECTOR_STORE_FAILED);
        assertThat(searched(store.search(new float[] {1, 0, 0}, 3, 2_000)).hits()).extracting("text")
                .containsExactly("old");
    }

    @Test
    void 질의_차원이_색인과_다르면_조용히_엉터리가_되지_않고_실패한다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h", List.of(chunk(0, "x", 1, 0, 0))));

        var r = store.search(new float[] {1, 0}, 3, 2_000);

        assertThat(r).isInstanceOf(SearchResult.Failed.class);
        assertThat(((SearchResult.Failed) r).error().code()).isEqualTo(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
    }

    @Test
    void 삭제하면_검색과_해시_목록에서_빠진다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "ha", List.of(chunk(0, "a", 1, 0, 0))));
        ok(store.replaceDocument("B", "t", "hb", List.of(chunk(0, "b", 0, 1, 0))));

        ok(store.deleteDocuments(List.of("A", "없는문서")));

        assertThat(ok(store.indexedHashes())).containsOnlyKeys("B");
        assertThat(searched(store.search(new float[] {1, 0, 0}, 5, 2_000)).hits()).extracting("documentId")
                .containsExactly("B");
    }

    @Test
    void 비유한_값은_저장하지_않는다() {
        ok(store.initMeta(META3));

        var r = store.replaceDocument("A", "t", "h", List.of(chunk(0, "x", Float.NaN, 0, 0)));

        assertThat(failedCode(r)).isEqualTo(ErrorCode.EMBEDDING_RESPONSE_INVALID);
        assertThat(ok(store.indexedHashes())).isEmpty();
    }

    @Test
    void 전체_재색인은_커밋_전까지_기존_색인을_지키고_커밋하면_한_번에_바뀐다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h", List.of(chunk(0, "old", 1, 0, 0))));
        IndexMeta meta2 = new IndexMeta("new-model", 2);

        ok(store.beginRebuild(meta2));
        assertThat(failedCode(store.beginRebuild(meta2))).isEqualTo(ErrorCode.VECTOR_STORE_FAILED); // 동시 재색인 거부

        // 재색인 중: 검색·메타는 기존, 쓰기는 대기 세대(해시 목록이 비어 있어 모든 문서를 새로 색인하게 된다)
        assertThat(ok(store.meta())).contains(META3);
        assertThat(ok(store.indexedHashes())).isEmpty();
        ok(store.replaceDocument("A", "t", "h-new", List.of(chunk(0, "new", 0, 1))));
        assertThat(searched(store.search(new float[] {1, 0, 0}, 3, 2_000)).hits()).extracting("text")
                .containsExactly("old");

        ok(store.commitRebuild());

        assertThat(ok(store.meta())).contains(meta2);
        assertThat(searched(store.search(new float[] {0, 1}, 3, 2_000)).hits()).extracting("text")
                .containsExactly("new");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_generation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_chunk", Integer.class)).isEqualTo(1);
        assertThat(failedCode(store.commitRebuild())).isEqualTo(ErrorCode.VECTOR_STORE_FAILED);
    }

    @Test
    void 재색인을_중단하면_기존_색인이_그대로이고_다시_시작할_수_있다() {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h", List.of(chunk(0, "old", 1, 0, 0))));

        ok(store.beginRebuild(new IndexMeta("m2", 2)));
        ok(store.replaceDocument("A", "t", "h2", List.of(chunk(0, "partial", 1, 0))));
        ok(store.abortRebuild());
        ok(store.abortRebuild()); // 진행 중이 없어도 성공

        assertThat(ok(store.meta())).contains(META3);
        assertThat(searched(store.search(new float[] {1, 0, 0}, 3, 2_000)).hits()).extracting("text")
                .containsExactly("old");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_generation", Integer.class)).isEqualTo(1);
        ok(store.beginRebuild(new IndexMeta("m3", 5)));
    }

    @Test
    void 검색이_잠금에_막혀도_기한에_포기하고_서버_쿼리를_취소한다() throws Exception {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h", List.of(chunk(0, "x", 1, 0, 0))));

        try (Connection blocker = ds.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.execute("LOCK TABLE rag_chunk IN ACCESS EXCLUSIVE MODE");
            }
            long t0 = System.nanoTime();
            SearchResult r = store.search(new float[] {1, 0, 0}, 3, 400);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;

            assertThat(r).isInstanceOf(SearchResult.TimedOut.class);
            assertThat(elapsed).isLessThan(1_500);
            // 취소된 쿼리가 서버에 남아 대기하지 않는다
            Thread.sleep(300);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE query LIKE '%rag_chunk k%' "
                    + "AND state = 'active' AND pid <> pg_backend_pid()", Integer.class)).isZero();
            blocker.rollback();
        }
        // 잠금이 풀리면 정상 검색이 다시 된다
        assertThat(searched(store.search(new float[] {1, 0, 0}, 3, 2_000)).hits()).hasSize(1);
    }

    @Test
    void 기한이_이미_없으면_쿼리를_시작하지_않고_타임아웃이다() {
        ok(store.initMeta(META3));
        assertThat(store.search(new float[] {1, 0, 0}, 3, 0)).isInstanceOf(SearchResult.TimedOut.class);
    }

    @Test
    void 메타와_pgvector_확장이_마이그레이션으로_준비된다() {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_extension WHERE extname = 'vector'", Integer.class))
                .isEqualTo(1);
        assertThat(Optional.ofNullable(jdbc.queryForObject("SELECT count(*) FROM rag_index_state", Integer.class)))
                .contains(1);
    }

    @Test
    void 막힌_검색이_상한만큼_쌓이면_다음_검색은_기다리지_않고_실패한다() throws Exception {
        ok(store.initMeta(META3));
        ok(store.replaceDocument("A", "t", "h", List.of(chunk(0, "x", 1, 0, 0))));
        var pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        try (Connection blocker = ds.getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement st = blocker.createStatement()) {
                st.execute("LOCK TABLE rag_chunk IN ACCESS EXCLUSIVE MODE");
            }
            for (int i = 0; i < 4; i++) {
                pool.submit(() -> store.search(new float[] {1, 0, 0}, 3, 4_000));
            }
            Thread.sleep(500);

            long t0 = System.nanoTime();
            SearchResult r = store.search(new float[] {1, 0, 0}, 3, 4_000);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;

            assertThat(r).isInstanceOf(SearchResult.Failed.class);
            assertThat(((SearchResult.Failed) r).error().detail()).isEqualTo("search_saturated");
            assertThat(elapsed).isLessThan(500);
            blocker.rollback();
        } finally {
            pool.shutdownNow();
        }
    }
}
