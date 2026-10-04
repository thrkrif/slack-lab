package com.slack.lab.adapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.TestImages;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.IndexReport;
import com.slack.lab.core.model.IndexReport.Outcome;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SearchResult;
import com.slack.lab.core.model.SourceDocument;
import com.slack.lab.core.port.DocumentSource;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.service.IndexingService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
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
 * 색인 파이프라인(M27)을 실제 pgvector Postgres 위에서 확인한다: 증분 키, 삭제와 안전장치, 부분 성공, 전체 재색인,
 * 잠금, 중간 중단 후 무결성. 임베딩은 글자 해시로 만든 결정적 8차원 벡터다.
 */
@Testcontainers
class IndexingPipelineTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(TestImages.POSTGRES);

    static final int DIM = 8;
    static HikariDataSource ds;
    static JdbcTemplate jdbc;

    PostgresVectorStore store;
    PostgresIndexLock lock;
    final Map<String, SourceDocument> source = new LinkedHashMap<>();
    final AtomicInteger embedCalls = new AtomicInteger();
    /** 청크 텍스트를 받아 실패를 돌려주면 그 결과를 쓴다. null이면 정상 임베딩. */
    Function<String, EmbeddingResult> inject = t -> null;

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
        jdbc.update("UPDATE rag_index_state SET live_generation = NULL, pending_generation = NULL");
        jdbc.update("DELETE FROM rag_generation");
        store = new PostgresVectorStore(ds);
        lock = new PostgresIndexLock(ds);
        source.clear();
        embedCalls.set(0);
        inject = t -> null;
    }

    @AfterEach
    void closeStore() {
        store.close();
    }

    static float[] vec(String text) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(text.getBytes());
            float[] v = new float[DIM];
            for (int i = 0; i < DIM; i++) {
                v[i] = (h[i] & 0xff) + 1f;
            }
            return v;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    EmbeddingClient embedding(int dimension) {
        return (text, remainingMs) -> {
            embedCalls.incrementAndGet();
            EmbeddingResult injected = inject.apply(text);
            if (injected != null) {
                return injected;
            }
            float[] v = vec(text);
            return new EmbeddingResult.Success(dimension == DIM ? v : new float[dimension], 1);
        };
    }

    DocumentSource src() {
        return () -> PortResult.ok(new ArrayList<>(source.values()));
    }

    IndexingService service(String model, int dim, double ratio) {
        return new IndexingService(src(), embedding(dim), store, lock,
                new IndexingService.Config(model, dim, 200, 20, ratio, 1_000, 2));
    }

    IndexingService service() {
        return service("test-model", DIM, 0.5);
    }

    void put(String id, String content) {
        source.put(id, new SourceDocument(id, "제목 " + id, content, Integer.toHexString(content.hashCode())));
    }

    int hits(String query) {
        var r = store.search(vec(query), 10, 2_000);
        assertThat(r).isInstanceOf(SearchResult.Success.class);
        return ((SearchResult.Success) r).hits().size();
    }

    Map<String, String> indexed() {
        return ((PortResult.Success<Map<String, String>>) store.indexedHashes()).value();
    }

    @Test
    void 처음_색인하면_모두_추가되고_두_번째는_임베딩을_다시_부르지_않는다() {
        put("A", "문서 A 내용");
        put("B", "문서 B 내용");

        IndexReport first = service().run(false, false);
        int callsAfterFirst = embedCalls.get();
        IndexReport second = service().run(false, false);

        assertThat(first.outcome()).isEqualTo(Outcome.OK);
        assertThat(first.added()).isEqualTo(2);
        assertThat(first.exitCode()).isZero();
        assertThat(second.unchanged()).isEqualTo(2);
        assertThat(second.added() + second.updated()).isZero();
        assertThat(embedCalls.get()).as("변경 없음 → 임베딩 호출 없음").isEqualTo(callsAfterFirst);
        assertThat(indexed()).containsOnlyKeys("A", "B");
    }

    @Test
    void 수정한_문서만_다시_색인하고_검색에_반영된다() {
        put("A", "원래 내용");
        put("B", "다른 문서");
        service().run(false, false);
        int before = embedCalls.get();

        put("A", "바뀐 내용");
        IndexReport r = service().run(false, false);

        assertThat(r.updated()).isEqualTo(1);
        assertThat(r.unchanged()).isEqualTo(1);
        assertThat(embedCalls.get() - before).isEqualTo(1);
        var hit = ((SearchResult.Success) store.search(vec("바뀐 내용"), 1, 2_000)).hits().get(0);
        assertThat(hit.documentId()).isEqualTo("A");
        assertThat(hit.text()).isEqualTo("바뀐 내용");
    }

    @Test
    void 출처에서_사라진_문서는_색인에서_지워진다() {
        put("A", "하나");
        put("B", "둘");
        put("C", "셋");
        service().run(false, false);

        source.remove("C");
        IndexReport r = service().run(false, false);

        assertThat(r.deleted()).isEqualTo(1);
        assertThat(indexed()).containsOnlyKeys("A", "B");
        assertThat(hits("셋")).isEqualTo(2);
    }

    @Test
    void 삭제_비율이_임계를_넘으면_아무것도_쓰지_않고_멈추고_확인하면_진행한다() {
        put("A", "하나");
        put("B", "둘");
        put("C", "셋");
        put("D", "넷");
        service().run(false, false);

        // 경로 설정 실수로 폴더가 비어 보이는 상황
        source.clear();
        IndexReport aborted = service().run(false, false);

        assertThat(aborted.outcome()).isEqualTo(Outcome.DELETE_ABORTED);
        assertThat(aborted.exitCode()).isEqualTo(5);
        assertThat(indexed()).containsOnlyKeys("A", "B", "C", "D");

        IndexReport confirmed = service().run(false, true);
        assertThat(confirmed.outcome()).isEqualTo(Outcome.OK);
        assertThat(confirmed.deleted()).isEqualTo(4);
        assertThat(indexed()).isEmpty();
    }

    @Test
    void 내용이_비게_된_문서는_색인에서_빠지고_빈_문서는_건너뛴다() {
        put("A", "내용");
        put("B", "다른 내용");
        put("C", "또 다른 내용");
        put("D", "마지막");
        service().run(false, false);

        put("A", "   \n\n  ");
        put("E", "");
        IndexReport r = service().run(false, false);

        assertThat(r.skippedEmpty()).isEqualTo(2);
        assertThat(r.deleted()).isEqualTo(1);
        assertThat(indexed()).containsOnlyKeys("B", "C", "D");
    }

    @Test
    void 한_문서의_임베딩이_실패해도_나머지는_색인하고_실패_목록과_비0_종료_코드로_알린다() {
        put("A", "정상 문서");
        put("BAD", "실패할 문서");
        put("C", "또 정상");
        inject = t -> t.contains("실패할") ? new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_HTTP_ERROR, "404"), 1, false) : null;

        IndexReport r = service().run(false, false);

        assertThat(r.outcome()).isEqualTo(Outcome.PARTIAL_FAILURE);
        assertThat(r.exitCode()).isEqualTo(1);
        assertThat(r.failed()).singleElement().satisfies(f -> {
            assertThat(f.documentId()).isEqualTo("BAD");
            assertThat(f.error().code()).isEqualTo(ErrorCode.EMBEDDING_HTTP_ERROR);
        });
        assertThat(r.summary()).contains("실패 BAD").doesNotContain("실패할 문서");
        assertThat(indexed()).containsOnlyKeys("A", "C");

        // 고치고 다시 돌리면 실패분만 색인한다
        inject = t -> null;
        int before = embedCalls.get();
        IndexReport again = service().run(false, false);
        assertThat(again.outcome()).isEqualTo(Outcome.OK);
        assertThat(again.added()).isEqualTo(1);
        assertThat(embedCalls.get() - before).isEqualTo(1);
    }

    @Test
    void 재시도_가능한_임베딩_실패는_다시_시도하고_영구_실패는_바로_포기한다() {
        put("A", "재시도할 문서");
        AtomicInteger n = new AtomicInteger();
        inject = t -> n.incrementAndGet() == 1
                ? new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED), 1, true) : null;

        assertThat(service().run(false, false).outcome()).isEqualTo(Outcome.OK);
        assertThat(n.get()).isEqualTo(2);

        reset();
        put("P", "영구 실패");
        AtomicInteger m = new AtomicInteger();
        inject = t -> {
            m.incrementAndGet();
            return new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_HTTP_ERROR, "404"), 1, false);
        };
        assertThat(service().run(false, false).outcome()).isEqualTo(Outcome.PARTIAL_FAILURE);
        assertThat(m.get()).isEqualTo(1);
    }

    @Test
    void 청킹_설정이나_모델이_바뀌면_같은_내용이어도_다시_색인한다() {
        put("A", "내용 ".repeat(100));
        service().run(false, false);
        int before = embedCalls.get();

        IndexingService other = new IndexingService(src(), embedding(DIM), store, lock,
                new IndexingService.Config("test-model", DIM, 120, 20, 0.5, 1_000, 2)); // 청크 크기 변경
        IndexReport r = other.run(false, false);

        assertThat(r.updated()).isEqualTo(1);
        assertThat(embedCalls.get()).isGreaterThan(before);
    }

    @Test
    void 모델_차원이_바뀌었는데_재색인을_요청하지_않으면_거부한다() {
        put("A", "내용");
        service().run(false, false);

        IndexReport r = service("new-model", 4, 0.5).run(false, false);

        assertThat(r.outcome()).isEqualTo(Outcome.STORE_OR_META_FAILED);
        assertThat(r.exitCode()).isEqualTo(4);
        assertThat(r.detail()).contains("--rebuild");
        assertThat(((PortResult.Success<java.util.Optional<IndexMeta>>) store.meta()).value())
                .contains(new IndexMeta("test-model", DIM));
    }

    @Test
    void 전체_재색인은_전부_성공했을_때만_게시된다() {
        put("A", "하나");
        put("B", "둘");
        service().run(false, false);

        IndexReport r = service("new-model", 4, 0.5).run(true, false);

        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(((PortResult.Success<java.util.Optional<IndexMeta>>) store.meta()).value())
                .contains(new IndexMeta("new-model", 4));
        assertThat(indexed()).containsOnlyKeys("A", "B");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_generation", Integer.class)).isEqualTo(1);
    }

    @Test
    void 전체_재색인_중_실패하면_대기_세대를_버리고_기존_색인을_지킨다() {
        put("A", "하나");
        put("B", "둘");
        service().run(false, false);
        inject = t -> t.equals("둘") ? new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_HTTP_ERROR, "500"), 1, false) : null;

        IndexReport r = service("new-model", 4, 0.5).run(true, false);

        assertThat(r.outcome()).isEqualTo(Outcome.REBUILD_ABORTED);
        assertThat(r.exitCode()).isEqualTo(6);
        assertThat(((PortResult.Success<java.util.Optional<IndexMeta>>) store.meta()).value())
                .contains(new IndexMeta("test-model", DIM));
        assertThat(hits("하나")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_generation", Integer.class)).isEqualTo(1);
    }

    @Test
    void 다른_색인이_잠금을_쥐고_있으면_기다리지_않고_종료_코드_3이다() {
        put("A", "내용");
        var held = lock.tryAcquire();
        assertThat(held).isPresent();
        try {
            IndexReport r = service().run(false, false);

            assertThat(r.outcome()).isEqualTo(Outcome.LOCKED);
            assertThat(r.exitCode()).isEqualTo(3);
            assertThat(embedCalls.get()).isZero();
        } finally {
            held.get().close();
        }
        assertThat(service().run(false, false).outcome()).as("풀리면 다시 돈다").isEqualTo(Outcome.OK);
    }

    @Test
    void 색인_도중_프로세스가_죽어도_검색이_깨지지_않고_다음_실행이_이어_받는다() {
        put("A", "첫 문서");
        put("B", "죽을 때 처리하던 문서");
        put("C", "아직 못 한 문서");
        inject = t -> {
            if (t.contains("죽을")) {
                throw new IllegalStateException("프로세스가 여기서 죽었다고 가정");
            }
            return null;
        };

        try {
            service().run(false, false);
            org.junit.jupiter.api.Assertions.fail("예외가 전파돼야 한다");
        } catch (IllegalStateException expected) {
            // 서비스가 삼키지 않는다 — 실제 kill과 같은 상황
        }

        // 일부만 색인됐지만 문서 단위로 온전하다: 청크 없는 문서도, 문서 없는 청크도 없다
        assertThat(indexed()).containsOnlyKeys("A");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_chunk k LEFT JOIN rag_document d "
                + "ON d.generation = k.generation AND d.document_id = k.document_id WHERE d.document_id IS NULL",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_document d WHERE NOT EXISTS (SELECT 1 FROM rag_chunk k "
                + "WHERE k.generation = d.generation AND k.document_id = d.document_id)", Integer.class)).isZero();
        assertThat(hits("첫 문서")).isEqualTo(1);
        assertThat(lock.tryAcquire()).as("잠금이 새지 않았다").isPresent().get().satisfies(h -> h.close());

        inject = t -> null;
        IndexReport resumed = service().run(false, false);
        assertThat(resumed.outcome()).isEqualTo(Outcome.OK);
        assertThat(resumed.added()).isEqualTo(2);
        assertThat(resumed.unchanged()).isEqualTo(1);
        assertThat(indexed()).containsOnlyKeys("A", "B", "C");
    }

    @Test
    void 재색인_도중_죽어_남은_대기_세대는_다음_실행이_버린다() {
        put("A", "하나");
        service().run(false, false);
        store.beginRebuild(new IndexMeta("zombie", 2)); // 이전 실행이 죽으며 남긴 대기 세대

        IndexReport r = service().run(false, false);

        assertThat(r.outcome()).isEqualTo(Outcome.OK);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_generation", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT pending_generation IS NULL FROM rag_index_state", Boolean.class)).isTrue();
    }

    @Test
    void 출처가_실패하면_색인을_건드리지_않는다() {
        put("A", "내용");
        service().run(false, false);
        DocumentSource broken = () -> PortResult.failed(ErrorInfo.of(ErrorCode.DOCUMENT_SOURCE_FAILED, "docs_dir_not_found"));

        IndexReport r = new IndexingService(broken, embedding(DIM), store, lock,
                new IndexingService.Config("test-model", DIM, 200, 20, 0.5, 1_000, 2)).run(false, true);

        assertThat(r.outcome()).isEqualTo(Outcome.SOURCE_FAILED);
        assertThat(r.exitCode()).isEqualTo(2);
        assertThat(indexed()).containsOnlyKeys("A");
    }

    @Test
    void 전체_재색인도_출처가_비어_보이면_확인_없이_기존_색인을_지우지_않는다() {
        put("A", "하나");
        put("B", "둘");
        service().run(false, false);
        source.clear(); // 경로 설정 실수

        IndexReport aborted = service("new-model", 4, 0.5).run(true, false);

        assertThat(aborted.outcome()).isEqualTo(Outcome.DELETE_ABORTED);
        assertThat(indexed()).containsOnlyKeys("A", "B");
        assertThat(((PortResult.Success<java.util.Optional<IndexMeta>>) store.meta()).value())
                .contains(new IndexMeta("test-model", DIM));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rag_generation", Integer.class)).isEqualTo(1);

        IndexReport confirmed = service("new-model", 4, 0.5).run(true, true);
        assertThat(confirmed.outcome()).isEqualTo(Outcome.OK);
        assertThat(indexed()).isEmpty();
    }

    @Test
    void 잠금을_잃으면_더_쓰지_않고_멈추며_대기_세대를_건드리지_않는다() {
        put("A", "첫 문서");
        put("B", "둘째 문서");
        put("C", "셋째 문서");
        // 첫 문서를 임베딩하는 동안 잠금을 쥔 서버 세션이 끊긴다(서버 재시작·강제 종료와 같은 효과)
        inject = t -> {
            if (t.contains("첫 문서")) {
                jdbc.queryForList("SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND granted", Integer.class)
                        .forEach(pid -> jdbc.queryForObject("SELECT pg_terminate_backend(?)", Boolean.class, pid));
                try {
                    Thread.sleep(200);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return null;
        };

        IndexReport r = service().run(false, false);

        assertThat(r.outcome()).isEqualTo(Outcome.STORE_OR_META_FAILED);
        assertThat(r.detail()).isEqualTo("lock_lost");
        assertThat(indexed()).as("잠금을 잃은 뒤에는 더 쓰지 않는다").containsOnlyKeys("A");
        assertThat(lock.tryAcquire()).as("끊긴 세션의 잠금은 서버가 풀었다").isPresent().get().satisfies(h -> h.close());
    }

    @Test
    void 설정은_NaN_삭제_비율을_거부한다() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new IndexingService.Config("m", 4, 200, 20, Double.NaN, 1_000, 2))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> new IndexingService.Config("m", 4, 200, 20, 1.5, 1_000, 2))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 잠금_핸들은_쥐고_있는_동안만_held이다() {
        var h = lock.tryAcquire().orElseThrow();
        assertThat(h.isHeld()).isTrue();
        h.close();
        assertThat(h.isHeld()).isFalse();
    }
}
