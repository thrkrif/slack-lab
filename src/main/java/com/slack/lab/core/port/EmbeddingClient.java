package com.slack.lab.core.port;

import com.slack.lab.core.model.EmbeddingResult;

/**
 * 텍스트를 벡터로 바꾼다(3단계 RAG). 구현체는 OpenAI 호환 스키마로만 통신한다(규칙 3과 같은 원칙).
 * 엔드포인트는 LLM과 같은 외부 유출 경로이므로 스위치 대상이다(ARCHITECTURE ADR-9).
 */
public interface EmbeddingClient {

    /**
     * @param remainingMs 호출 시작 시점까지 남은 기한. 모델 적재·대기·호출을 합쳐 이 시간 안에 끝나야 하고, 넘으면
     *     {@link EmbeddingResult.TimedOut}으로 포기한다. 예외를 던지지 않는다
     */
    EmbeddingResult embed(String text, long remainingMs);
}
