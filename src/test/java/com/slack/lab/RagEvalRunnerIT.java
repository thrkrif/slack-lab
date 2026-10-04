package com.slack.lab;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 평가 CLI(indexer 역할의 --eval 모드)가 색인된 pgvector를 실제로 검색해 요약·종료 판정을 내는지 확인한다. 임베딩만 스텁이다. */
@Testcontainers
class RagEvalRunnerIT {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(TestImages.POSTGRES);

    static HttpServer embeddings;

    @BeforeAll
    static void startStub() throws Exception {
        embeddings = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        embeddings.createContext("/v1/embeddings", ex -> {
            String in = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String v = in.contains("커넥션") ? "[1,0,0,0]" : in.contains("디스크") ? "[0,1,0,0]" : "[0,0,1,0]";
            byte[] out = ("{\"data\":[{\"embedding\":" + v + "}]}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        embeddings.start();
    }

    @AfterAll
    static void stopStub() {
        embeddings.stop(0);
    }

    @BeforeEach
    void resetIndex() {
        var jdbc = new JdbcTemplate(new DriverManagerDataSource(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword()));
        if (jdbc.queryForObject("SELECT to_regclass('rag_generation') IS NOT NULL", Boolean.class)) {
            jdbc.update("UPDATE rag_index_state SET live_generation = NULL, pending_generation = NULL");
            jdbc.update("DELETE FROM rag_generation");
        }
    }

    /** 마지막 실행에서 평가 러너가 정한 종료 코드(색인 실행이면 -1). */
    static int lastExit = -1;

    private static String run(Path docs, String... extra) {
        return run(docs, true, extra);
    }

    private static String run(Path docs, boolean withDocsDir, String... extra) {
        PrintStream original = System.out;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
        lastExit = -1;
        try {
            List<String> args = new ArrayList<>(List.of("--app.role=indexer", "--slack.signing-secret=s", "--slack.bot-token=t",
                    "--llm.model=m", "--llm.client=echo", "--postgres.url=" + PG.getJdbcUrl(),
                    "--postgres.username=" + PG.getUsername(), "--postgres.password=" + PG.getPassword(), "--rag.enabled=true",
                    "--rag.embedding-model=stub", "--rag.embedding-dimension=4",
                    "--rag.embedding-base-url=http://127.0.0.1:" + embeddings.getAddress().getPort() + "/v1",
                    "--rag.index.exit-after-run=false", "--rag.retrieval.min-score=0.5"));
            if (withDocsDir) {
                args.add("--rag.index.docs-dir=" + docs);
            }
            args.addAll(List.of(extra));
            try (ConfigurableApplicationContext ctx = new SpringApplicationBuilder(SlackLabApplication.class)
                    .run(args.toArray(String[]::new))) {
                lastExit = ctx.getBean(com.slack.lab.adapter.cli.RagEvalRunner.class).lastExitCode();
            }
        } finally {
            System.setOut(original);
        }
        return captured.toString(StandardCharsets.UTF_8);
    }

    static void write(Path dir, String name, String content) throws Exception {
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name), content);
    }

    /** final 규격(정답 24 + 정답 없음 6)을 채운 질문 JSON. {@code extraTuning}은 앞에 쉼표 없는 추가 요소(또는 빈 문자열). */
    static String finalQuestions(String answerableText, String expected, int answerable, int none, String extraTuning) {
        List<String> items = new ArrayList<>();
        for (int i = 1; i <= answerable; i++) {
            items.add("{\"id\":\"F-A" + i + "\",\"set\":\"final\",\"kind\":\"mention\",\"expected\":[\"" + expected
                    + "\"],\"text\":\"" + answerableText + " " + i + "\"}");
        }
        for (int i = 1; i <= none; i++) {
            items.add("{\"id\":\"F-N" + i + "\",\"set\":\"final\",\"kind\":\"mention\",\"expected\":[],\"text\":\"점심 메뉴 " + i
                    + "\"}");
        }
        if (!extraTuning.isBlank()) {
            items.add(extraTuning);
        }
        return "{\"questions\":[" + String.join(",", items) + "]}";
    }

    static final String TUNING_Q = "{\"id\":\"T-1\",\"set\":\"tuning\",\"kind\":\"alarm\",\"expected\":[\"DB-1\"],\"title\":\"커넥션 경보\",\"body\":\"풀 사용률 높음\"}";

    private Path docsWithDb(Path tmp) throws Exception {
        Path docs = tmp.resolve("documents");
        write(docs, "DB-1.md", "---\nid: DB-1\ntitle: 커넥션 풀\n---\n커넥션 풀 고갈 대응");
        write(docs, "OPS-1.md", "---\nid: OPS-1\ntitle: 디스크\n---\n디스크 사용량 점검");
        assertThat(run(docs)).contains("outcome=OK").contains("added=2");
        return docs;
    }

    @Test
    void 기본은_튜닝_세트만_돌리고_final_순위는_출력하지_않는다(@TempDir Path tmp) throws Exception {
        Path docs = docsWithDb(tmp);
        Path eval = tmp.resolve("eval");
        write(eval, "questions.json", finalQuestions("커넥션 문제", "DB-1", 24, 6, TUNING_Q));

        String out = run(docs, "--eval", "--eval.dir=" + eval);

        assertThat(out).contains("== tuning (조정용, 판정 아님)").contains("T-1").doesNotContain("== final").doesNotContain("F-A1");
        assertThat(lastExit).isZero();
    }

    @Test
    void final을_명시하면_규격_24와_6에서_hit와_근거_미주입을_세고_합격이면_종료_코드_0이다(@TempDir Path tmp) throws Exception {
        Path docs = docsWithDb(tmp);
        Path eval = tmp.resolve("eval");
        write(eval, "questions.json", finalQuestions("커넥션 문제", "DB-1", 24, 6, TUNING_Q));

        String out = run(docs, "--eval", "--eval.dir=" + eval, "--eval.set=all");

        assertThat(out).contains("== final (합격 판정)").contains("hit@3 24/24").contains("근거 미주입 6/6").contains("판정: 합격");
        assertThat(out).contains("== tuning (조정용, 판정 아님)");
        assertThat(lastExit).isZero();
    }

    @Test
    void 정답_문서를_못_찾으면_불합격이고_종료_코드_1이다(@TempDir Path tmp) throws Exception {
        Path docs = tmp.resolve("documents");
        write(docs, "DB-1.md", "---\nid: DB-1\ntitle: 커넥션 풀\n---\n커넥션 풀 고갈 대응");
        // 정답 문서보다 질문에 가까운 문서가 3개 이상 있어야 상위 3개에서 밀려난다(색인이 1개뿐이면 항상 hit이다)
        for (int i = 1; i <= 3; i++) {
            write(docs, "OPS-" + i + ".md", "---\nid: OPS-" + i + "\ntitle: 디스크 " + i + "\n---\n디스크 사용량 점검 " + i);
        }
        run(docs);
        Path eval = tmp.resolve("eval");
        write(eval, "questions.json", finalQuestions("디스크 문제", "DB-1", 24, 6, ""));

        String out = run(docs, "--eval", "--eval.dir=" + eval, "--eval.set=final");

        assertThat(out).contains("== final (합격 판정)").contains("hit@3 0/24").contains("MISS").contains("판정: 불합격");
        assertThat(lastExit).isEqualTo(1);
    }

    @Test
    void final이_규격_24와_6이_아니면_합격시키지_않고_종료_코드_2다(@TempDir Path tmp) throws Exception {
        Path docs = docsWithDb(tmp);
        Path eval = tmp.resolve("eval");
        write(eval, "questions.json", finalQuestions("커넥션 문제", "DB-1", 1, 1, ""));

        String out = run(docs, "--eval", "--eval.dir=" + eval, "--eval.set=final");

        assertThat(out).contains("규격(정답 24 + 정답 없음 6)이 아니라 판정하지 않는다").doesNotContain("판정: 합격");
        assertThat(lastExit).isEqualTo(2);
    }

    @Test
    void 답변_쌍_양식_옵션은_표만_출력하고_검색하지_않는다(@TempDir Path tmp) throws Exception {
        Path docs = docsWithDb(tmp);
        Path eval = tmp.resolve("eval");
        write(eval, "questions.json", "{\"questions\":[{\"id\":\"T-1\",\"set\":\"tuning\",\"kind\":\"mention\",\"expected\":[\"DB-1\"],\"text\":\"q\"}]}");

        String out = run(docs, "--eval", "--eval.dir=" + eval, "--eval.pairs");

        assertThat(out).contains("| 질문 ID |").contains("| T-1 | mention | DB-1 |").doesNotContain("hit@3");
        assertThat(lastExit).isZero();
    }

    @Test
    void 평가_모드는_문서_경로_설정_없이도_뜬다_scripts_rag_eval의_두_번째_단계(@TempDir Path tmp) throws Exception {
        Path docs = docsWithDb(tmp);
        Path eval = tmp.resolve("eval");
        write(eval, "questions.json", finalQuestions("커넥션 문제", "DB-1", 24, 6, TUNING_Q));

        String out = run(docs, false, "--eval", "--eval.dir=" + eval);

        assertThat(out).contains("== tuning (조정용, 판정 아님)").doesNotContain("docs-dir");
        assertThat(lastExit).isZero();
    }

    @Test
    void 색인_모드는_문서_경로가_없으면_이유를_출력한다_종료_코드는_IndexingPipelineTest가_본다(@TempDir Path tmp) throws Exception {
        String out = run(tmp, false);

        assertThat(out).contains("outcome=SOURCE_FAILED").contains("docs_dir_not_configured");
    }

    @Test
    void 질문_파일이_없으면_이유를_출력하고_종료_코드_2다(@TempDir Path tmp) throws Exception {
        Path docs = tmp.resolve("documents");
        write(docs, "DB-1.md", "커넥션 풀 고갈 대응");

        String out = run(docs, "--eval", "--eval.dir=" + tmp.resolve("없음"));

        assertThat(out).contains("평가 질문을 읽지 못했다");
        assertThat(lastExit).isEqualTo(2);
    }

    @Test
    void 색인이_비어_있어도_검색_불가가_아니라_hit_0으로_센다_정답이_있는_세트는_합격하지_않는다(@TempDir Path tmp) throws Exception {
        Path docs = tmp.resolve("documents");
        write(docs, "DB-1.md", "---\nid: DB-1\ntitle: 커넥션 풀\n---\n커넥션 풀 고갈 대응");
        // 색인하지 않은 DB(리셋 직후)에서 final을 돌리면 합격할 수 없다
        Path eval = tmp.resolve("eval");
        write(eval, "questions.json", finalQuestions("커넥션 문제", "DB-1", 24, 6, ""));

        String out = run(docs, "--eval", "--eval.dir=" + eval, "--eval.set=final");

        assertThat(out).contains("hit@3 0/24").contains("판정: 불합격");
        assertThat(lastExit).isEqualTo(1);
    }
}
