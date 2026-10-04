package com.slack.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.core.model.SearchResult;
import com.slack.lab.core.port.VectorStore;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 색인 CLI를 컨텍스트째 돌린다(indexer 역할: 러너 → IndexingService → 로컬 마크다운 → 임베딩 HTTP → pgvector). 임베딩 서버만
 * 스텁이고 나머지는 실제다. 웹 포트·큐 소비자 없이 DB만으로 뜨는지도 함께 본다.
 */
@Testcontainers
class RagIndexerIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(TestImages.POSTGRES);

    static HttpServer embeddings;
    static final int DIM = 4;

    @BeforeAll
    static void startStub() throws Exception {
        embeddings = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        embeddings.createContext("/v1/embeddings", ex -> {
            String in = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            // 입력에 "커넥션"이 있으면 [1,0,0,0], 아니면 [0,1,0,0]인 아주 단순한 의미 공간
            String v = in.contains("커넥션") ? "[1,0,0,0]" : "[0,1,0,0]";
            byte[] out = ("{\"data\":[{\"embedding\":" + v + "}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        embeddings.start();
    }

    // 테스트가 같은 DB를 공유하므로 앞 테스트의 색인이 남지 않게 비운다(테이블은 첫 실행의 마이그레이션이 만든다).
    @BeforeEach
    void resetIndex() {
        var jdbc = new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(PG.getJdbcUrl(),
                PG.getUsername(), PG.getPassword()));
        if (jdbc.queryForObject("SELECT to_regclass('rag_generation') IS NOT NULL", Boolean.class)) {
            jdbc.update("UPDATE rag_index_state SET live_generation = NULL, pending_generation = NULL");
            jdbc.update("DELETE FROM rag_generation");
        }
    }

    @AfterAll
    static void stopStub() {
        embeddings.stop(0);
    }

    private static String run(Path docs, ConfigurableApplicationContext[] holder, String... extra) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        try {
            java.util.List<String> args = new java.util.ArrayList<>(java.util.List.of("--app.role=indexer",
                    "--slack.signing-secret=s", "--slack.bot-token=t", "--llm.model=m", "--llm.client=echo",
                    "--postgres.url=" + PG.getJdbcUrl(), "--postgres.username=" + PG.getUsername(),
                    "--postgres.password=" + PG.getPassword(), "--rag.enabled=true", "--rag.embedding-model=stub",
                    "--rag.embedding-dimension=" + DIM,
                    "--rag.embedding-base-url=http://127.0.0.1:" + embeddings.getAddress().getPort() + "/v1",
                    "--rag.index.docs-dir=" + docs, "--rag.index.exit-after-run=false"));
            args.addAll(java.util.List.of(extra));
            holder[0] = new SpringApplicationBuilder(SlackLabApplication.class).run(args.toArray(String[]::new));
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    @Test
    void 색인_CLI가_문서를_색인하고_같은_컨텍스트에서_검색된다_웹_포트나_큐_소비자는_없다(@TempDir Path docs) throws Exception {
        Files.writeString(docs.resolve("DB-003.md"), "---\ntitle: 커넥션 풀 고갈\n---\n커넥션 풀이 고갈되어 타임아웃이 났다.");
        Files.writeString(docs.resolve("OPS-012.md"), "# 점검 절차\n\n디스크 사용량을 확인한다.");

        ConfigurableApplicationContext[] holder = new ConfigurableApplicationContext[1];
        String out = run(docs, holder);
        try (ConfigurableApplicationContext ctx = holder[0]) {
            assertThat(out).contains("outcome=OK").contains("added=2").contains("failed=0");
            assertThat(ctx).isNotInstanceOf(org.springframework.web.context.WebApplicationContext.class);
            assertThat(ctx.getBeanNamesForType(com.slack.lab.core.service.EventProcessor.class)).isEmpty();

            VectorStore store = ctx.getBean(VectorStore.class);
            var r = (SearchResult.Success) store.search(new float[] {1, 0, 0, 0}, 1, 2_000);
            assertThat(r.hits()).singleElement().satisfies(h -> {
                assertThat(h.documentId()).isEqualTo("DB-003");
                assertThat(h.title()).isEqualTo("커넥션 풀 고갈");
            });
            assertThat(new JdbcTemplate(ctx.getBean(javax.sql.DataSource.class))
                    .queryForObject("SELECT count(*) FROM rag_document", Integer.class)).isEqualTo(2);
        }

        // 두 번째 실행은 변경이 없다
        ConfigurableApplicationContext[] again = new ConfigurableApplicationContext[1];
        String out2 = run(docs, again);
        again[0].close();
        assertThat(out2).contains("outcome=OK").contains("unchanged=2").contains("added=0");
    }

    @Test
    void 문서_경로가_없으면_색인을_건드리지_않고_출처_실패를_알린다(@TempDir Path docs) throws Exception {
        Files.writeString(docs.resolve("A.md"), "커넥션 문서");
        ConfigurableApplicationContext[] first = new ConfigurableApplicationContext[1];
        run(docs, first);
        first[0].close();

        ConfigurableApplicationContext[] second = new ConfigurableApplicationContext[1];
        String out = run(docs.resolve("없는경로"), second, "--rag.index.max-delete-ratio=1.0");
        try (ConfigurableApplicationContext ctx = second[0]) {
            assertThat(out).contains("outcome=SOURCE_FAILED").contains("docs_dir_not_found");
            assertThat(new JdbcTemplate(ctx.getBean(javax.sql.DataSource.class))
                    .queryForObject("SELECT count(*) FROM rag_document", Integer.class)).isEqualTo(1);
        }
    }
}
