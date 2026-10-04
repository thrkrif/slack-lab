package com.slack.lab.adapter.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.RagProperties;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleEmbeddingClientTest {

    static RagProperties props(String baseUrl, int dim) {
        return new RagProperties(true, baseUrl, "bge-m3", dim, 5_000, 500, true, false, false, List.of("localhost"));
    }

    /** 경로 /v1/embeddings에 고정 응답을 주는 로컬 스텁. delayMs 동안 응답을 미룬다. */
    static HttpServer stub(int status, String body, long delayMs, AtomicReference<String> lastBody) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            String in = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (lastBody != null) {
                lastBody.set(in);
            }
            try {
                Thread.sleep(delayMs);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] out = body.getBytes(StandardCharsets.UTF_8);
            try {
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(status, out.length);
                ex.getResponseBody().write(out);
            } catch (IOException ignored) {
                // 클라이언트가 이미 끊었다(기한 초과 시나리오)
            } finally {
                ex.close();
            }
        });
        server.start();
        return server;
    }

    static String url(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/v1";
    }

    static EmbeddingResult call(HttpServer s, int dim, long budgetMs) {
        try (var client = new OpenAiCompatibleEmbeddingClient(props(url(s), dim), new ObjectMapper())) {
            return client.embed("커넥션 풀 고갈", budgetMs);
        }
    }

    @Test
    void 정상_응답을_벡터로_파싱하고_요청은_OpenAI_호환_스키마다() throws Exception {
        var seen = new AtomicReference<String>();
        var s = stub(200, "{\"data\":[{\"embedding\":[0.5,-0.25,1.0]}],\"model\":\"bge-m3\"}", 0, seen);
        try {
            var r = call(s, 3, 5_000);

            assertThat(r).isInstanceOf(EmbeddingResult.Success.class);
            assertThat(((EmbeddingResult.Success) r).vector()).containsExactly(0.5f, -0.25f, 1.0f);
            assertThat(seen.get()).contains("\"model\":\"bge-m3\"").contains("\"input\":\"커넥션 풀 고갈\"");
        } finally {
            s.stop(0);
        }
    }

    @Test
    void 차원이_설정과_다르면_쓰지_않고_재시도_불가_실패다() throws Exception {
        var s = stub(200, "{\"data\":[{\"embedding\":[0.1,0.2]}]}", 0, null);
        try {
            var r = (EmbeddingResult.Failed) call(s, 1024, 5_000);

            assertThat(r.error().code()).isEqualTo(ErrorCode.EMBEDDING_DIMENSION_MISMATCH);
            assertThat(r.error().detail()).contains("got=2").contains("expected=1024");
            assertThat(r.retryable()).isFalse();
        } finally {
            s.stop(0);
        }
    }

    @Test
    void 모델_없음_404는_재시도_불가_5xx는_재시도_가능이다() throws Exception {
        var notFound = stub(404, "{\"error\":{\"message\":\"model not found\"}}", 0, null);
        var unavailable = stub(503, "{}", 0, null);
        try {
            var r404 = (EmbeddingResult.Failed) call(notFound, 3, 5_000);
            var r503 = (EmbeddingResult.Failed) call(unavailable, 3, 5_000);

            assertThat(r404.error().code()).isEqualTo(ErrorCode.EMBEDDING_HTTP_ERROR);
            assertThat(r404.error().detail()).isEqualTo("404");
            assertThat(r404.retryable()).isFalse();
            assertThat(r503.retryable()).isTrue();
        } finally {
            notFound.stop(0);
            unavailable.stop(0);
        }
    }

    @Test
    void 응답이_깨졌거나_비어_있으면_응답_오류다() throws Exception {
        for (String body : new String[] {"not json", "{}", "{\"data\":[]}", "{\"data\":[{\"embedding\":[]}]}",
                "{\"data\":[{\"embedding\":[\"a\",\"b\",\"c\"]}]}"}) {
            var s = stub(200, body, 0, null);
            try {
                var r = (EmbeddingResult.Failed) call(s, 3, 5_000);
                assertThat(r.error().code()).as(body).isEqualTo(ErrorCode.EMBEDDING_RESPONSE_INVALID);
                assertThat(r.retryable()).isFalse();
            } finally {
                s.stop(0);
            }
        }
    }

    @Test
    void 기한을_넘기면_기다리지_않고_TimedOut이다() throws Exception {
        var s = stub(200, "{\"data\":[{\"embedding\":[1,2,3]}]}", 3_000, null);
        try {
            long t0 = System.nanoTime();
            var r = call(s, 3, 400);
            long elapsed = (System.nanoTime() - t0) / 1_000_000;

            assertThat(r).isInstanceOf(EmbeddingResult.TimedOut.class);
            assertThat(elapsed).isLessThan(1_500);
        } finally {
            s.stop(0);
        }
    }

    @Test
    void 연결이_안_되면_재시도_가능_실패이고_예산이_없으면_시작하지_않는다() throws Exception {
        var s = stub(200, "{}", 0, null);
        String closedUrl = url(s);
        s.stop(0);
        try (var client = new OpenAiCompatibleEmbeddingClient(props(closedUrl, 3), new ObjectMapper())) {
            var refused = (EmbeddingResult.Failed) client.embed("x", 2_000);
            var noBudget = (EmbeddingResult.Failed) client.embed("x", 0);

            assertThat(refused.error().code()).isEqualTo(ErrorCode.EMBEDDING_CONNECT_FAILED);
            assertThat(refused.retryable()).isTrue();
            assertThat(noBudget.error().code()).isEqualTo(ErrorCode.EMBEDDING_NO_BUDGET);
        }
    }

    @Test
    void 리다이렉트는_따라가지_않는다() throws Exception {
        HttpServer redirect = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        redirect.createContext("/v1/embeddings", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Location", "http://evil.invalid/v1/embeddings");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        redirect.start();
        try {
            var r = (EmbeddingResult.Failed) call(redirect, 3, 3_000);

            assertThat(r.error().code()).isEqualTo(ErrorCode.EMBEDDING_HTTP_ERROR);
            assertThat(r.error().detail()).isEqualTo("302");
        } finally {
            redirect.stop(0);
        }
    }
}
