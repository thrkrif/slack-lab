package com.slack.lab.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class OpenAiCompatibleLlmClientTest {

    static LlmProperties props(String baseUrl) {
        return new LlmProperties(LlmProperties.Client.OLLAMA, baseUrl, "qwen2.5:7b", 512, "30m", 50_000, 500, true);
    }

    @Test
    void 정상_응답을_파싱한다() throws Exception {
        String body = """
                {"choices":[{"message":{"content":"안녕하세요"}}]}""";
        try (var stub = StubOpenAiServer.chatRespondsWith(body, 200)) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.chat("안녕", 5_000);
            assertThat(result).isInstanceOf(LlmResult.Success.class);
            assertThat(((LlmResult.Success) result).text()).isEqualTo("안녕하세요");
        }
    }

    @Test
    void 서버_오류_5xx는_명확한_실패로_분류한다() throws Exception {
        try (var stub = StubOpenAiServer.chatRespondsWith("{\"error\":\"x\"}", 500)) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.chat("안녕", 5_000);
            assertThat(result).isInstanceOf(LlmResult.Failed.class);
        }
    }

    @Test
    void 기한을_넘기면_취소되고_TimedOut을_반환한다() throws Exception {
        try (var stub = StubOpenAiServer.chatHangs()) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            long start = System.nanoTime();
            var result = client.chat("안녕", 500);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(result).isInstanceOf(LlmResult.TimedOut.class);
            // 취소가 실제로 걸려 5초(스텁의 sleep)까지 기다리지 않았는지 확인 — M1.5 결론의 재확인
            assertThat(elapsedMs).isLessThan(3_000);
        }
    }

    @Test
    void 남은_기한이_없으면_호출하지_않고_실패한다() {
        var client = new OpenAiCompatibleLlmClient(props("http://127.0.0.1:1/v1"), new ObjectMapper());
        assertThat(client.chat("x", 0)).isInstanceOf(LlmResult.Failed.class);
    }

    @Test
    void 모델이_목록에_있으면_검증을_통과한다() throws Exception {
        String body = """
                {"data":[{"id":"qwen2.5:7b"},{"id":"other"}]}""";
        try (var stub = StubOpenAiServer.modelsRespondsWith(body)) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            client.verifyModelExists();
        }
    }

    @Test
    void 모델이_목록에_없으면_기동_실패_예외를_던진다() throws Exception {
        String body = """
                {"data":[{"id":"other-model"}]}""";
        try (var stub = StubOpenAiServer.modelsRespondsWith(body)) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            assertThatThrownBy(client::verifyModelExists)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("qwen2.5:7b");
        }
    }

    @Test
    void 모델_목록_호출_자체가_실패하면_기동_실패_예외를_던진다() {
        var client = new OpenAiCompatibleLlmClient(props("http://127.0.0.1:1/v1"), new ObjectMapper());
        assertThatThrownBy(client::verifyModelExists).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 모델_목록_응답이_멈추면_기한_안에_취소되고_예외를_던진다() throws Exception {
        // codex 재검토 지적: 이전 modelsHangs()는 헤더조차 안 보내 request.timeout만으로도 통과했다.
        // 여기서는 헤더를 보낸 뒤 본문에서 정지시켜, 취소가 실제로 소켓을 닫는지 서버 쪽에서 확인한다(M1.5 방식).
        try (var stub = StubOpenAiServer.modelsHeadersThenHang()) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            long start = System.nanoTime();
            assertThatThrownBy(client::verifyModelExists).isInstanceOf(IllegalStateException.class);
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            assertThat(elapsedMs).isLessThan(5_000);

            long peerClosedMs = stub.peerClosedAtMs().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(peerClosedMs).as("서버가 소켓 종료를 감지해야 한다(헤더만으로는 못 끊는다는 M1.5 결론의 재확인)").isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void 잘못된_JSON_응답은_예외_없이_실패로_분류한다() throws Exception {
        try (var stub = StubOpenAiServer.chatRespondsWith("not json", 200)) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.chat("안녕", 5_000);
            assertThat(result).isInstanceOf(LlmResult.Failed.class);
        }
    }

    @Test
    void choices가_비어있으면_실패로_분류하고_빈_성공을_주지_않는다() throws Exception {
        try (var stub = StubOpenAiServer.chatRespondsWith("{\"choices\":[]}", 200)) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.chat("안녕", 5_000);
            assertThat(result).isInstanceOf(LlmResult.Failed.class);
        }
    }

    @Test
    void 호출_스레드가_인터럽트되면_진행중_요청도_취소되고_소켓이_닫힌다() throws Exception {
        // codex 재검토 지적: 이전 테스트는 스레드 종료만 확인해 수정 전 구현도 통과했다.
        // 헤더까지 받은 뒤 인터럽트하고, 서버가 소켓 종료를 직접 감지하는지·반환 결과·인터럽트 상태까지 확인한다.
        try (var stub = StubOpenAiServer.chatHeadersThenHangs()) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            var resultRef = new java.util.concurrent.atomic.AtomicReference<LlmResult>();
            var interruptedAfterReturn = new java.util.concurrent.atomic.AtomicBoolean();
            Thread caller = new Thread(() -> {
                resultRef.set(client.chat("안녕", 30_000));
                interruptedAfterReturn.set(Thread.currentThread().isInterrupted());
            });
            caller.start();
            Thread.sleep(500); // 헤더가 도착할 시간을 준다(모든 반복 실행이 스텁의 sendResponseHeaders 이후가 되도록)
            caller.interrupt();
            caller.join(5_000);

            assertThat(caller.isAlive()).isFalse();
            assertThat(resultRef.get()).isInstanceOf(LlmResult.Failed.class);
            assertThat(interruptedAfterReturn).as("인터럽트 상태가 복원돼야 한다").isTrue();

            long peerClosedMs = stub.peerClosedAtMs().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(peerClosedMs).as("future.cancel(true)가 실제로 소켓을 닫아야 한다").isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void content가_빈_문자열이면_실패로_분류한다() throws Exception {
        String body = """
                {"choices":[{"message":{"content":""}}]}""";
        try (var stub = StubOpenAiServer.chatRespondsWith(body, 200)) {
            var client = new OpenAiCompatibleLlmClient(props(stub.baseUrl()), new ObjectMapper());
            var result = client.chat("안녕", 5_000);
            assertThat(result).isInstanceOf(LlmResult.Failed.class);
        }
    }
}
