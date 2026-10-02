package com.slack.lab.adapter.slack;

import com.slack.lab.core.model.ReplyMatch;
import com.slack.lab.config.SlackProperties;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SlackThreadClientTest {

    /** 페이지별 응답을 순서대로 돌려주는 conversations.replies 스텁. 요청 URI를 기록한다. */
    static final class Stub implements AutoCloseable {
        final HttpServer server;
        final List<String> requests = new ArrayList<>();
        final List<String> auth = new ArrayList<>();
        private int page;

        Stub(List<String> pages) throws Exception {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/conversations.replies", ex -> {
                requests.add(ex.getRequestURI().toString());
                auth.add(ex.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = pages.get(Math.min(page++, pages.size() - 1)).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(200, bytes.length);
                try (var os = ex.getResponseBody()) {
                    os.write(bytes);
                }
            });
            server.start();
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }

    static SlackThreadClient client(Stub stub) {
        return new SlackThreadClient(new SlackProperties("s", "xoxb-test", stub.baseUrl(), 10_000), new ObjectMapper());
    }

    static String msg(String ts, String eventId, String attemptId) {
        return "{\"ts\":\"" + ts + "\",\"text\":\"본문\",\"metadata\":{\"event_type\":\"slack_lab_reply\","
                + "\"event_payload\":{\"event_id\":\"" + eventId + "\",\"attempt_id\":\"" + attemptId + "\"}}}";
    }

    @Test
    void 스레드에서_event_id가_일치하는_봇_답글만_찾는다() throws Exception {
        String body = "{\"ok\":true,\"has_more\":false,\"messages\":[{\"ts\":\"1.1\",\"text\":\"멘션\"},"
                + msg("2.2", "Ev1", "a1") + "," + msg("3.3", "Ev-other", "a9") + "]}";
        try (var stub = new Stub(List.of(body))) {
            var result = client(stub).findReplies("C1", "1.1", "Ev1");

            assertThat(result.failed()).isFalse();
            assertThat(result.complete()).isTrue();
            assertThat(result.matches()).containsExactly(new ReplyMatch("2.2", "a1"));
            assertThat(stub.requests.get(0)).contains("channel=C1").contains("ts=1.1")
                    .contains("include_all_metadata=true");
            assertThat(stub.auth.get(0)).isEqualTo("Bearer xoxb-test");
        }
    }

    @Test
    void 답글이_없으면_끝까지_읽었을_때만_없음으로_본다() throws Exception {
        try (var stub = new Stub(List.of("{\"ok\":true,\"has_more\":false,\"messages\":[{\"ts\":\"1.1\"}]}"))) {
            var result = client(stub).findReplies("C1", "1.1", "Ev1");
            assertThat(result.matches()).isEmpty();
            assertThat(result.complete()).isTrue();
        }
    }

    @Test
    void 여러_페이지에_걸친_스레드는_커서를_따라가며_읽는다() throws Exception {
        String p1 = "{\"ok\":true,\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"CUR2\"},\"messages\":[{\"ts\":\"1.1\"}]}";
        String p2 = "{\"ok\":true,\"has_more\":false,\"messages\":[" + msg("9.9", "Ev1", "a1") + "]}";
        try (var stub = new Stub(List.of(p1, p2))) {
            var result = client(stub).findReplies("C1", "1.1", "Ev1");

            assertThat(result.matches()).extracting(ReplyMatch::ts).containsExactly("9.9");
            assertThat(stub.requests).hasSize(2);
            assertThat(stub.requests.get(1)).contains("cursor=CUR2");
        }
    }

    @Test
    void 페이지_상한에_걸리면_완전하지_않다고_알린다() throws Exception {
        String more = "{\"ok\":true,\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"X\"},\"messages\":[{\"ts\":\"1.1\"}]}";
        try (var stub = new Stub(List.of(more))) {
            var result = client(stub).findReplies("C1", "1.1", "Ev1");
            assertThat(result.complete()).isFalse();
            assertThat(result.failed()).isFalse();
        }
    }

    @Test
    void ok_false는_실패로_알린다_스코프_부족이_대표적이다() throws Exception {
        try (var stub = new Stub(List.of("{\"ok\":false,\"error\":\"missing_scope\"}"))) {
            var result = client(stub).findReplies("C1", "1.1", "Ev1");
            assertThat(result.failed()).isTrue();
            assertThat(result.error()).isEqualTo("missing_scope");
            assertThat(result.complete()).isFalse();
        }
    }

    @Test
    void 연결_실패도_실패로_알린다() {
        var client = new SlackThreadClient(new SlackProperties("s", "t", "http://127.0.0.1:1", 10_000), new ObjectMapper());
        var result = client.findReplies("C1", "1.1", "Ev1");
        assertThat(result.failed()).isTrue();
        assertThat(result.complete()).isFalse();
    }

    @Test
    void 더_있다면서_커서가_없으면_완전하지_않다() throws Exception {
        try (var stub = new Stub(List.of("{\"ok\":true,\"has_more\":true,\"messages\":[]}"))) {
            var result = client(stub).findReplies("C1", "1.1", "Ev1");
            assertThat(result.complete()).isFalse();
            assertThat(result.failed()).isFalse();
        }
    }

    @Test
    void messages가_없는_ok_응답은_실패로_본다() throws Exception {
        try (var stub = new Stub(List.of("{\"ok\":true}"))) {
            var result = client(stub).findReplies("C1", "1.1", "Ev1");
            assertThat(result.failed()).isTrue();
            assertThat(result.complete()).isFalse();
        }
    }
}
