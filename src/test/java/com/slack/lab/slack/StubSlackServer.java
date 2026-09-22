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

    /**
     * 헤더는 보내고 본문에서 정지한다. 취소 후 소켓 종료를 서버가 직접 감지한다(M1.5와 동일한 방식).
     * codex 리뷰 지적: 고정 Content-Length를 예고한 뒤 그보다 적게 쓰고 닫으면, 스트림 자체 close()가
     * 길이 불일치로 IOException을 던져 "상대가 끊었다"고 오판할 수 있었다. 청크 전송(length=0)으로 바꿔
     * 자체 close가 성공하도록 하고, 반복 상한도 넉넉히 둬 정상적으로 끝까지 쓸 수 있게 한다 — 그러면
     * IOException은 오직 클라이언트가 실제로 연결을 끊었을 때만 발생한다.
     */
    static StubSlackServer headersThenHangs() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        StubSlackServer stub = new StubSlackServer(server);
        server.createContext("/chat.postMessage", ex -> {
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
                stub.peerClosedAtMs.complete(-1L); // 상한까지 안 끊김 — close()는 청크 종료만 쓰고 정상 성공한다
            } catch (IOException e) {
                stub.peerClosedAtMs.complete((System.nanoTime() - start) / 1_000_000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
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
