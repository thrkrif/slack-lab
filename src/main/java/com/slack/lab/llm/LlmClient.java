package com.slack.lab.llm;

/** 벤더 중립 LLM 호출 인터페이스. 구현체는 OpenAI 호환 스키마로만 통신한다(AGENTS.md 규칙 3). */
public interface LlmClient {

    /**
     * @param prompt        사용자 프롬프트 (봇 멘션 토큰은 호출자가 이미 제거)
     * @param remainingMs   호출 시작 시점까지 남은 LLM 단계 기한. 연결·응답 읽기를 합쳐 이 시간 안에 끝나야 한다
     * @return 파싱된 답변. 실패·기한 초과는 {@link LlmResult}의 실패 분기로 돌려주고 예외를 던지지 않는다
     */
    LlmResult chat(String prompt, long remainingMs);
}
