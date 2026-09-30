package com.slack.lab.llm;

import java.util.List;

/** 벤더 중립 LLM 호출 인터페이스. 구현체는 OpenAI 호환 스키마로만 통신한다(AGENTS.md 규칙 3). */
public interface LlmClient {

    /**
     * @param messages      대화 순서대로의 user/assistant 메시지. 마지막이 이번 질문이다(봇 멘션 토큰은 호출자가 이미
     *                      제거). 시스템 프롬프트는 구현체가 앞에 붙인다
     * @param remainingMs   호출 시작 시점까지 남은 LLM 단계 기한. 연결·응답 읽기를 합쳐 이 시간 안에 끝나야 한다
     * @return 파싱된 답변. 실패·기한 초과는 {@link LlmResult}의 실패 분기로 돌려주고 예외를 던지지 않는다
     */
    LlmResult chat(List<LlmMessage> messages, long remainingMs);

    /** 문맥 없는 단일 질문. */
    default LlmResult chat(String prompt, long remainingMs) {
        return chat(List.of(LlmMessage.user(prompt)), remainingMs);
    }
}
