package com.slack.lab.slack;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SlackClientTest {

    static SlackProperties props(String baseUrl) {
        return new SlackProperties("secret", "xoxb-test", baseUrl, 10_000);
    }

    @Test
    void ok_true면_성공이고_ts를_돌려준다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("{\"ok\":true,\"ts\":\"100.1\"}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C1", "99.9", "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Success("100.1"));
        }
    }

    @Test
    void http_200이어도_ok_false면_실패로_분류한다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("{\"ok\":false,\"error\":\"channel_not_found\"}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C-bad", null, "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Failed("channel_not_found"));
        }
    }

    @Test
    void 기한_초과로_취소되면_결과_불명이다() throws Exception {
        try (var stub = StubSlackServer.hangs()) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            long start = System.nanoTime();
            var result = client.postMessage("C1", null, "안녕", 500);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(result).isInstanceOf(SlackSendResult.Unknown.class);
            assertThat(elapsedMs).isLessThan(3_000);
        }
    }

    @Test
    void 연결_자체가_안_되면_명확한_실패다() {
        var client = new SlackClient(props("http://127.0.0.1:1"), new ObjectMapper());
        var result = client.postMessage("C1", null, "안녕", 3_000);
        assertThat(result).isInstanceOf(SlackSendResult.Failed.class);
        assertThat(((SlackSendResult.Failed) result).reason()).startsWith("connect_failed");
    }

    @Test
    void 남은_기한이_없으면_발신하지_않고_실패로_기록한다() {
        var client = new SlackClient(props("http://127.0.0.1:1"), new ObjectMapper());
        var result = client.postMessage("C1", null, "안녕", 0);
        assertThat(result).isEqualTo(new SlackSendResult.Failed("budget_exhausted"));
    }

    @Test
    void 취소_타이머_예약이_실패하면_이미_제출된_요청을_취소하고_결과_불명을_돌려준다() throws Exception {
        // codex critic REVISE MAJOR-2: cancelTimer.schedule()이 try/catch 밖에 있으면, sendAsync는 이미
        // 제출된 상태에서 예약만 실패해도 예외가 그대로 전파돼 워커가 영구 실패(DEAD+DLQ)로 오분류한다.
        // 여기서는 취소 타이머를 강제로 종료시켜 schedule()이 RejectedExecutionException을 던지게 한다.
        try (var stub = StubSlackServer.hangs()) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            shutdownCancelTimer(client);

            long start = System.nanoTime();
            var result = client.postMessage("C1", null, "안녕", 5_000);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            assertThat(result).isInstanceOf(SlackSendResult.Unknown.class);
            assertThat(((SlackSendResult.Unknown) result).reason()).startsWith("cancel_schedule_failed");
            // 스텁이 응답하지 않는 서버라도, 타이머가 없어 무기한 기다리지 않고 즉시 반환해야 한다 —
            // future를 취소했다는 방증이다.
            assertThat(elapsedMs).isLessThan(3_000);
        }
    }

    private static void shutdownCancelTimer(SlackClient client) throws Exception {
        var field = SlackClient.class.getDeclaredField("cancelTimer");
        field.setAccessible(true);
        ((java.util.concurrent.ScheduledExecutorService) field.get(client)).shutdownNow();
    }

    @Test
    void 호출_스레드가_인터럽트되면_진행중_요청도_취소되고_소켓이_닫힌다() throws Exception {
        // M4 codex 리뷰와 같은 결함: InterruptedException에서 future를 취소하지 않으면 본문 정체 요청이 남는다.
        // 스레드 종료만으로는 수정 전 구현도 통과하므로, 헤더 수신 후 인터럽트하고 서버가 소켓 종료를 감지하는지까지 본다.
        try (var stub = StubSlackServer.headersThenHangs()) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var resultRef = new java.util.concurrent.atomic.AtomicReference<SlackSendResult>();
            Thread caller = new Thread(() -> resultRef.set(client.postMessage("C1", null, "안녕", 30_000)));
            caller.start();
            Thread.sleep(500); // 헤더가 도착할 시간을 준다
            caller.interrupt();
            caller.join(5_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(resultRef.get()).isInstanceOf(SlackSendResult.Unknown.class);

            long peerClosedMs = stub.peerClosedAtMs().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(peerClosedMs).as("future.cancel(true)가 실제로 소켓을 닫아야 한다").isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void rate_limit_429는_재시도_가능한_실패로_분류하고_Retry_After가_없으면_0이다() throws Exception {
        // M13: rate limit은 미전송이 확실하고 재시도 가능하다(PLAN "429 → Retry-After 반영").
        try (var stub = StubSlackServer.respondsWith("rate limited", 429)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C1", null, "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Failed("rate_limited", true, 0));
        }
    }

    @Test
    void 상태코드_4xx는_영구_실패로_분류한다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("bad request", 400)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C1", null, "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Failed("status=400"));
            assertThat(((SlackSendResult.Failed) result).retryable()).isFalse();
        }
    }

    @Test
    void 서버_오류_5xx는_결과_불명이다() throws Exception {
        // codex 리뷰 지적: 5xx는 Slack 쪽에서 일부 처리됐을 가능성이 있어 명확한 실패로 단정할 수 없다.
        try (var stub = StubSlackServer.respondsWith("internal error", 500)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C1", null, "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Unknown("status=500"));
        }
    }

    @Test
    void internal_error는_결과_불명으로_분류한다() throws Exception {
        // Slack 문서: internal_error·fatal_error는 일부 처리가 성공했을 수 있다.
        try (var stub = StubSlackServer.respondsWith("{\"ok\":false,\"error\":\"internal_error\"}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C1", null, "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Unknown("internal_error"));
        }
    }

    @Test
    void ok_필드가_없거나_boolean이_아니면_결과_불명이다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("{}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            assertThat(client.postMessage("C1", null, "안녕", 5_000)).isInstanceOf(SlackSendResult.Unknown.class);
        }
        try (var stub = StubSlackServer.respondsWith("{\"ok\":\"true\"}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            assertThat(client.postMessage("C1", null, "안녕", 5_000)).isInstanceOf(SlackSendResult.Unknown.class);
        }
    }

    @Test
    void ok_true인데_ts가_없으면_결과_불명이다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("{\"ok\":true}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C1", null, "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Unknown("success_without_ts"));
        }
    }

    @Test
    void 잘못된_URL로_인한_요청_준비_실패는_예외_없이_명확한_실패다() {
        var client = new SlackClient(props("not a url"), new ObjectMapper());
        var result = client.postMessage("C1", null, "안녕", 5_000);
        assertThat(result).isInstanceOf(SlackSendResult.Failed.class);
        assertThat(((SlackSendResult.Failed) result).reason()).startsWith("request_build_failed");
    }

    @Test
    void 메타데이터를_주면_event_type과_payload가_요청_본문에_실린다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("{\"ok\":true,\"ts\":\"100.1\"}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            client.postMessage("C1", "99.9", "안녕", 5_000, new ReplyMetadata("Ev1", "att-1"));

            var body = new ObjectMapper().readTree(stub.lastBody());
            assertThat(body.path("metadata").path("event_type").asText()).isEqualTo("slack_lab_reply");
            assertThat(body.path("metadata").path("event_payload").path("event_id").asText()).isEqualTo("Ev1");
            assertThat(body.path("metadata").path("event_payload").path("attempt_id").asText()).isEqualTo("att-1");
            assertThat(body.path("thread_ts").asText()).isEqualTo("99.9");
        }
    }

    @Test
    void 메타데이터가_없으면_metadata_필드를_보내지_않는다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("{\"ok\":true,\"ts\":\"100.1\"}", 200)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            client.postMessage("C1", null, "안녕", 5_000);

            var body = new ObjectMapper().readTree(stub.lastBody());
            assertThat(body.has("metadata")).isFalse();
            assertThat(body.has("thread_ts")).isFalse();
        }
    }
}
