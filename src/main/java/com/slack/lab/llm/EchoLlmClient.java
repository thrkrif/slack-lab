package com.slack.lab.llm;

/**
 * 모델 없이 Slack 왕복만 검증하는 스텁. M8 경계 실험(slow-mode)이 이 클라이언트로 재전송 경계를 관측한다 —
 * 실제 모델을 쓰면 추론 시간이 섞여 재전송 경계 관측이 오염된다(PLAN §3 M8-2).
 */
public class EchoLlmClient implements LlmClient {

    @Override
    public LlmResult chat(String prompt, long remainingMs) {
        if (remainingMs <= 0) {
            return new LlmResult.Failed("남은 기한 없음", 0);
        }
        return new LlmResult.Success("echo: " + prompt, 0);
    }
}
