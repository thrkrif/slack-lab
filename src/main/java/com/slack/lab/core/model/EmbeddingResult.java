package com.slack.lab.core.model;

/** 임베딩 호출 결과. {@link LlmResult}와 같은 방식으로 예외 없이 분기한다. */
public sealed interface EmbeddingResult {

    /**
     * @param vector 모델이 돌려준 벡터(방어적 복사, 동등성은 참조 기준). 차원 검증은 호출자(색인·검색)가 index_meta와
     *     맞춰 한다
     */
    record Success(float[] vector, long elapsedMs) implements EmbeddingResult {
        public Success {
            vector = vector.clone();
        }

        @Override
        public float[] vector() {
            return vector.clone();
        }
    }

    /** 기한 초과로 포기. 이후 도착한 결과는 쓰지 않는다. */
    record TimedOut(long elapsedMs) implements EmbeddingResult {}

    /**
     * @param retryable 같은 입력을 다시 보내면 성공할 수 있는가. 연결 실패·5xx는 true, 4xx·모델 없음·응답 파싱 실패는
     *     false다. 이 값은 <b>색인 CLI의 재시도 판단</b>에만 쓴다. 질의 임베딩이 실패해도 메시지 재처리(RetryRequested)를
     *     만들지 않는다 — RAG 없이 답하는 폴백이다(PLAN M28)
     */
    record Failed(ErrorInfo error, long elapsedMs, boolean retryable) implements EmbeddingResult {}
}
