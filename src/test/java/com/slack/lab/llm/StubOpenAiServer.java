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

    /**
     * codex 리뷰 지적: 고정 Content-Length를 예고한 뒤 그보다 적게 쓰고 닫으면, 스트림 자체 close()가
     * 길이 불일치로 IOException을 던져 "상대가 끊었다"고 오판할 수 있었다. 청크 전송(length=0)으로 바꿔
     * 자체 close가 성공하게 하고, 반복 상한도 넉넉히 둬야 IOException이 오직 실제 취소에서만 발생한다.
     */
    private static StubOpenAiServer startHeadersThenHang(String path) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StubOpenAiServer stub = new StubOpenAiServer(server);
        server.createContext(path, ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 0); // 0 = 청크 전송, Content-Length 예고 없음
            long start = System.nanoTime();
            try (var out = ex.getResponseBody()) {
                for (int i = 0; i < 300; i++) { // 최대 30초 — 테스트의 어떤 대기 시간보다 넉넉하다
                    Thread.sleep(100);
                    out.write('x');
                    out.flush();
                }
                stub.peerClosedAtMs.complete(-1L); // 상한까지 안 끊김
            } catch (IOException e) {
                // 클라이언트가 취소해 연결을 끊으면(FIN/RST) 여기서 쓰기가 실패한다 — 소켓이 실제로 닫혔다는 증거.
                stub.peerClosedAtMs.complete((System.nanoTime() - start) / 1_000_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
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
