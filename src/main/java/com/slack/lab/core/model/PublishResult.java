package com.slack.lab.core.model;

/**
 * 발행 결과 3분류(PLAN 2단계 M12). 컨트롤러는 이 값만 보고 HTTP 응답을 정한다 — 내구성 있는 저장을
 * 확인받지 못하면 200을 주지 않는다(ARCHITECTURE §3.1, 규칙 11).
 */
public sealed interface PublishResult {

    record Enqueued(String messageId) implements PublishResult {}

    /** 저장 자체가 실패했음이 확실하다(연결 거부 등). 재전송 시 새로 시도된다. */
    record Failed(ErrorInfo error) implements PublishResult {}

    /** `XADD`는 됐을 수 있으나 `WAITAOF`로 로컬 fsync를 확인하지 못했다. 저장 여부가 불명확하다. */
    record Unconfirmed(ErrorInfo error) implements PublishResult {}
}
