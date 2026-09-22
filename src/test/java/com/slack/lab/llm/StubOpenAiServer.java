package com.slack.lab.llm;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/** OpenAI 호환 엔드포인트를 흉내 내는 테스트용 로컬 스텁. */
final class StubOpenAiServer implements AutoCloseable {

    private final HttpServer server;
    /** {@link #chatHeadersThenHangs()}·{@link #modelsHeadersThenHang()} 전용: 클라이언트 소켓이 실제로 닫힌 시각(ms). */
    private final CompletableFuture<Long> peerClosedAtMs = new CompletableFuture<>();

    private StubOpenAiServer(HttpServer server) {
        this.server = server;
    }

    static StubOpenAiServer chatRespondsWith(String json, int statusCode) throws IOException {
        return start("/v1/chat/completions", ex -> json, statusCode);
    }

    static StubOpenAiServer chatHangs() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/v1/chat/completions", ex -> {
            // 요청은 읽되 응답을 영원히 보내지 않는다 (M1.5 무응답 스텁과 동일한 성격).
            ex.getRequestBody().readAllBytes();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        return new StubOpenAiServer(server);
    }

    /**
     * 헤더는 보내고 본문에서 정지한다 — M1.5 결론(`HttpRequest.timeout`은 헤더까지만 덮음)이 실제로 적용되는
     * 지점을 재현한다. 이후 클라이언트가 취소하면 소켓 종료를 서버가 직접 감지해 {@link #peerClosedAtMs()}로 알려준다.
     */
    static StubOpenAiServer chatHeadersThenHangs() throws IOException {
        return startHeadersThenHang("/v1/chat/completions");
    }

    static StubOpenAiServer modelsRespondsWith(String json) throws IOException {
        return start("/v1/models", ex -> json, 200);
    }

    static StubOpenAiServer modelsHeadersThenHang() throws IOException {
        return startHeadersThenHang("/v1/models");
    }

    private static StubOpenAiServer startHeadersThenHang(String path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StubOpenAiServer stub = new StubOpenAiServer(server);
        server.createContext(path, ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 1000); // Content-Length 예고, 본문은 안 보냄 — 헤더까지만 나간 상태
            long start = System.nanoTime();
            try (var out = ex.getResponseBody()) {
                for (int i = 0; i < 40; i++) { // 최대 4초, 200ms마다 1바이트 써서 상대 종료를 감지
                    Thread.sleep(100);
                    out.write('x');
                    out.flush();
                }
            } catch (IOException e) {
                // 클라이언트가 취소해 연결을 끊으면(FIN/RST) 여기서 쓰기가 실패한다 — 소켓이 실제로 닫혔다는 증거.
                stub.peerClosedAtMs.complete((System.nanoTime() - start) / 1_000_000);
                return;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stub.peerClosedAtMs.complete(-1L); // 40회 안에 끊기지 않음
        });
        server.start();
        return stub;
    }

    private static StubOpenAiServer start(String path, Function<HttpExchange, String> body, int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(path, ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] bytes = body.apply(ex).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (var os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return new StubOpenAiServer(server);
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    /** {@link #chatHeadersThenHangs()}류 전용. 클라이언트 취소 후 서버가 소켓 종료를 감지한 시각(ms), 못 감지하면 -1. */
    CompletableFuture<Long> peerClosedAtMs() {
        return peerClosedAtMs;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
