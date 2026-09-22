package com.slack.lab.llm;

/** 호출자가 예외를 catch하지 않고 분기할 수 있게 결과를 타입으로 드러낸다. */
public sealed interface LlmResult {

    record Success(String text, long elapsedMs) implements LlmResult {}

    /** 기한 초과로 취소됨. M1.5 결론대로 cancel(true)로 소켓을 닫은 뒤에도 도달한다. */
    record TimedOut(long elapsedMs) implements LlmResult {}

    /** 연결 거부·5xx·파싱 실패 등 명확한 실패. */
    record Failed(String reason, long elapsedMs) implements LlmResult {}
}
