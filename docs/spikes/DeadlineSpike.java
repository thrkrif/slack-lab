import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * M1.5 기한 강제 스파이크. 실행: {@code java docs/spikes/DeadlineSpike.java}
 *
 * 왜 서버 쪽에서 재는가: 클라이언트가 예외를 받았다는 사실만으로는 소켓이 닫혔다고 말할 수 없다.
 * 스텁이 상대 종료(EOF/RST)를 직접 감지한 시각이 "소켓이 실제로 닫힌" 증거다.
 * 스텁은 응답을 멈춘 뒤에도 연결을 붙잡고 있으므로, 클라이언트가 안 닫으면 관측 창(OBSERVE_MS) 끝까지 감지되지 않는다.
 */
public class DeadlineSpike {

    static final long DEADLINE_MS = 2_000;
    static final long OBSERVE_MS = 6_000; // 기한 이후 소켓 종료를 기다리는 관측 창

    enum Stub { HEADERS_ONLY, BODY_TRUNCATED, ACCEPT_SILENT }

    enum Mode {
        REQUEST_TIMEOUT("request.timeout(2s)만"),
        CANCEL_ONLY("timeout 없음 + 2s에 future.cancel(true)"),
        BOTH("request.timeout(2s) + 2s에 cancel(true)");
        final String label;
        Mode(String label) { this.label = label; }
    }

    static final AtomicLong T0 = new AtomicLong();

    static long since() { return (System.nanoTime() - T0.get()) / 1_000_000; }

    record ServerObs(long requestSeenMs, long peerClosedMs, String how) {}

    static class StubServer implements AutoCloseable {
        final ServerSocket ss;
        final Stub stub;
        volatile ServerObs obs;
        final Thread thread;

        StubServer(Stub stub) throws Exception {
            this.stub = stub;
            this.ss = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
            this.thread = Thread.ofPlatform().daemon().start(this::serve);
        }

        int port() { return ss.getLocalPort(); }

        void serve() {
            try (Socket s = ss.accept()) {
                s.setSoTimeout((int) (DEADLINE_MS + OBSERVE_MS + 2_000));
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                // ACCEPT_SILENT도 요청 바이트는 읽는다: 커널 버퍼에 쌓이게 두면 상대 종료 감지가 흐려진다
                // (그래도 응답은 절대 쓰지 않는다).
                byte[] buf = new byte[4096];
                StringBuilder sb = new StringBuilder();
                while (!sb.toString().contains("\r\n\r\n")) {
                    int n = in.read(buf);
                    if (n < 0) { obs = new ServerObs(-1, since(), "요청 도중 EOF"); return; }
                    sb.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                }
                long seen = since();
                switch (stub) {
                    case HEADERS_ONLY -> out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                            + "Content-Length: 1000\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                    case BODY_TRUNCATED -> out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\n"
                            + "Content-Length: 1000\r\n\r\n" + "x".repeat(100)).getBytes(StandardCharsets.ISO_8859_1));
                    case ACCEPT_SILENT -> { /* 아무것도 쓰지 않는다 */ }
                }
                out.flush();
                try {
                    int n = in.read(buf); // 상대가 닫으면 -1 또는 RST 예외
                    obs = new ServerObs(seen, since(), n < 0 ? "EOF(FIN)" : "예상 밖 데이터 수신");
                } catch (java.net.SocketTimeoutException e) {
                    obs = new ServerObs(seen, -1, "관측 창 끝까지 닫히지 않음");
                } catch (java.io.IOException e) {
                    obs = new ServerObs(seen, since(), "RST/" + e.getClass().getSimpleName());
                }
            } catch (Exception e) {
                obs = new ServerObs(-1, -1, "서버 오류 " + e);
            }
        }

        @Override public void close() throws Exception { ss.close(); }
    }

    public static void main(String[] args) throws Exception {
        ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor();
        System.out.printf("java=%s deadline=%dms observe=%dms%n%n",
                System.getProperty("java.version"), DEADLINE_MS, OBSERVE_MS);
        System.out.println("| 스텁 | 방식 | 클라 실패 시각(ms) | 클라 예외 | 서버가 소켓 종료 감지(ms) | 감지 방식 |");
        System.out.println("|---|---|---|---|---|---|");

        for (Stub stub : Stub.values()) {
            for (Mode mode : Mode.values()) {
                run(stub, mode, timer);
            }
        }
        timer.shutdownNow();
    }

    static void run(Stub stub, Mode mode, ScheduledExecutorService timer) throws Exception {
        try (StubServer server = new StubServer(stub)) {
            // 요청마다 새 클라이언트: 이전 실험의 keep-alive 연결이 섞이면 종료 시각이 오염된다.
            HttpClient client = HttpClient.newBuilder()
                    .version(HttpClient.Version.HTTP_1_1) // Ollama는 h1. 평문 h2c 업그레이드 헤더가 끼면 변수가 늘어난다
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/"));
            if (mode != Mode.CANCEL_ONLY) rb.timeout(Duration.ofMillis(DEADLINE_MS));

            T0.set(System.nanoTime());
            CompletableFuture<HttpResponse<String>> f =
                    client.sendAsync(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (mode != Mode.REQUEST_TIMEOUT) {
                timer.schedule(() -> f.cancel(true), DEADLINE_MS, TimeUnit.MILLISECONDS);
            }

            long clientMs;
            String ex;
            try {
                f.get(DEADLINE_MS + OBSERVE_MS, TimeUnit.MILLISECONDS);
                clientMs = since();
                ex = "성공(예상 밖)";
            } catch (java.util.concurrent.TimeoutException e) {
                clientMs = -1;
                ex = "관측 창까지 미완료";
            } catch (Exception e) {
                clientMs = since();
                Throwable c = e instanceof java.util.concurrent.ExecutionException || e instanceof CompletionException
                        ? e.getCause() : e;
                ex = c.getClass().getSimpleName();
            }
            // 클라 실패 뒤에도 서버가 소켓 종료를 감지할 시간을 준다.
            server.thread.join(OBSERVE_MS + 1_000);
            ServerObs o = server.obs;
            System.out.printf("| %s | %s | %s | %s | %s | %s |%n", stub, mode.label,
                    clientMs < 0 ? "-" : clientMs, ex,
                    o == null || o.peerClosedMs() < 0 ? "-" : o.peerClosedMs(),
                    o == null ? "관측 없음" : o.how());
        }
    }
}
