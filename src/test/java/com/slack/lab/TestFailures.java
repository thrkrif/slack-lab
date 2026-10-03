package com.slack.lab;

import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.Failure;
import com.slack.lab.core.model.MessageKind;
import com.slack.lab.core.model.ProcessingStage;

/** 테스트에서 실패 값을 짧게 만드는 도우미. 상세 문자열은 단언에서 구분하려는 용도다. */
public final class TestFailures {

    private TestFailures() {}

    /** 답변 발신 단계의 실패. */
    public static Failure send(String detail) {
        return new Failure(ProcessingStage.SEND, MessageKind.ANSWER, ErrorInfo.of(ErrorCode.SLACK_API_ERROR, detail));
    }

    public static Failure send(MessageKind kind, ErrorCode code, String detail) {
        return new Failure(ProcessingStage.SEND, kind, ErrorInfo.of(code, detail));
    }

    public static Failure llm(ErrorCode code, String detail) {
        return new Failure(ProcessingStage.LLM, MessageKind.ANSWER, ErrorInfo.of(code, detail));
    }

    public static Failure llmTimeout() {
        return llm(ErrorCode.LLM_TIMEOUT, "");
    }
}
