package com.slack.lab.adapter.llm;

import com.slack.lab.core.port.LlmClient;
import com.slack.lab.core.model.LlmMessage;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.LlmResult;
import java.util.List;

/**
 * 모델 없이 Slack 왕복만 검증하는 스텁. M8 경계 실험(slow-mode)이 이 클라이언트로 재전송 경계를 관측한다 —
 * 실제 모델을 쓰면 추론 시간이 섞여 재전송 경계 관측이 오염된다(PLAN §3 M8-2).
 */
public class EchoLlmClient implements LlmClient {

    @Override
    public LlmResult chat(List<LlmMessage> messages, long remainingMs) {
        if (remainingMs <= 0) {
            return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_NO_BUDGET), 0, false);
        }
        // 문맥은 무시하고 마지막(이번 질문)만 되돌린다 — 모델 없이 왕복만 보는 스텁이다.
        return new LlmResult.Success("echo: " + messages.get(messages.size() - 1).content(), 0);
    }
}
