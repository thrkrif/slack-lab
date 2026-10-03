package com.slack.lab.core.model;

/** 호출자가 예외를 catch하지 않고 분기할 수 있게 결과를 타입으로 드러낸다. */
public sealed interface LlmResult {

    record Success(String text, long elapsedMs) implements LlmResult {}

    /** 기한 초과로 취소됨. M1.5 결론대로 cancel(true)로 소켓을 닫은 뒤에도 도달한다. 항상 재시도 가능하다(M13). */
    record TimedOut(long elapsedMs) implements LlmResult {}

    /**
     * 명확한 실패.
     *
     * @param retryable 재시도 가능 여부(M13, PLAN "M13 재시도·DLQ"). 연결 실패·5xx는 true, 4xx·모델 없음·
     *     요청 직렬화 실패·응답 파싱 실패는 false다 — 같은 입력을 다시 보내도 결과가 달라지지 않는다.
     */
    record Failed(ErrorInfo error, long elapsedMs, boolean retryable) implements LlmResult {}
}
