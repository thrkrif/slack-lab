package com.slack.lab.core.model;

/** 요청 분류 결과. 실패는 예외가 아니라 값이다 — 호출자는 장애 질문으로 폴백한다(fail-open). */
public sealed interface ClassifyResult {

    record Classified(RequestKind kind, long elapsedMs) implements ClassifyResult {}

    /**
     * @param retryable 같은 요청을 다시 보내면 달라질 수 있는가. 모델 없음(4xx)·응답 형식 오류는 false, 연결 실패·5xx·기한 초과는 true.
     *     분류 실패 자체는 처리 재시도 사유가 아니고, 기동 확인이 "영구 실패라 기동을 거부할지"를 가르는 데 쓴다
     */
    record Failed(ErrorInfo error, long elapsedMs, boolean retryable) implements ClassifyResult {}
}
