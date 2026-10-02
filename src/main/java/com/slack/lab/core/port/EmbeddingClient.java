package com.slack.lab.core.port;

/**
 * 텍스트를 벡터로 바꾼다(3단계 RAG). 아직 구현체가 없다 — 자리만 잡아 둔 포트다.
 * 엔드포인트는 LLM과 같은 외부 유출 경로이므로 스위치 대상이다(ARCHITECTURE ADR-9).
 */
public interface EmbeddingClient {

    /** 실패·기한 초과 처리 방식(예외 없는 결과 타입)은 3단계에서 {@code LlmResult}처럼 정한다. */
    float[] embed(String text, long remainingMs);
}
