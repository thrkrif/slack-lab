package com.slack.lab.slack;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import com.sun.net.httpserver.HttpServer;

/** `chat.postMessage`를 흉내 내는 테스트용 로컬 스텁. */
final class StubSlackServer implements AutoCloseable {

    private final HttpServer server;
    /** {@link #headersThenHangs()} 전용: 클라이언트 소켓이 실제로 닫힌 시각(ms), 못 감지하면 -1. */
    private final CompletableFuture<Long> peerClosedAtMs = new CompletableFuture<>();

    private StubSlackServer(HttpServer server) {
        this.server = server;
    }

    static StubSlackServer respondsWith(String json, int status) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat.postMessage", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, bytes.length);
            try (var os = ex.getResponseBody()) {
                os.write(bytes);
            }
        });
        server.start();
        return new StubSlackServer(server);
    }

    static StubSlackServer hangs() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/chat.postMessage", ex -> {
            ex.getRequestBody().readAllBytes();
            try {
                Thread.sleep(30_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        return new StubSlackServer(server);
    }

    /** 헤더는 보내고 본문에서 정지한다. 취소 후 소켓 종료를 서버가 직접 감지한다(M1.5와 동일한 방식). */
    static StubSlackServer headersThenHangs() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StubSlackServer stub = new StubSlackServer(server);
        server.createContext("/chat.postMessage", ex -> {
            ex.getRequestBody().readAllBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, 1000);
            long start = System.nanoTime();
            try (var out = ex.getResponseBody()) {
                for (int i = 0; i < 40; i++) {
                    Thread.sleep(100);
                    out.write('x');
                    out.flush();
                }
            } catch (IOException e) {
                stub.peerClosedAtMs.complete((System.nanoTime() - start) / 1_000_000);
                return;
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            stub.peerClosedAtMs.complete(-1L);
        });
        server.start();
        return stub;
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    CompletableFuture<Long> peerClosedAtMs() {
        return peerClosedAtMs;
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
