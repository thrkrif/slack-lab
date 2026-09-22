package com.slack.lab.llm;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class EchoLlmClientTest {

    final EchoLlmClient client = new EchoLlmClient();

    @Test
    void 프롬프트를_그대로_돌려준다() {
        var result = client.chat("안녕", 1_000);
        assertThat(result).isInstanceOf(LlmResult.Success.class);
        assertThat(((LlmResult.Success) result).text()).contains("안녕");
    }

    @Test
    void 남은_기한이_없으면_실패한다() {
        assertThat(client.chat("x", 0)).isInstanceOf(LlmResult.Failed.class);
    }
}
