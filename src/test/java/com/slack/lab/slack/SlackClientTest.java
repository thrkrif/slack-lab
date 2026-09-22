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
    void 예외적_상태코드는_실패로_분류한다() throws Exception {
        try (var stub = StubSlackServer.respondsWith("rate limited", 429)) {
            var client = new SlackClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.postMessage("C1", null, "안녕", 5_000);
            assertThat(result).isEqualTo(new SlackSendResult.Failed("status=429"));
        }
    }
}
