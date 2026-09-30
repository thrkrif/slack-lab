package com.slack.lab.slack;

/**
 * Slack 발신 결과 3분류(ARCHITECTURE §4.1). {@code chat.postMessage}는 실패해도 HTTP 200을 주므로
 * 본문 {@code ok} 필드로 성공을 판단하고, 연결 끊김·읽기 타임아웃은 명확한 실패가 아니라 결과 불명이다.
 */
public sealed interface SlackSendResult {

    record Success(String ts) implements SlackSendResult {}

    /**
     * 전송되지 않았음이 확실한 실패.
     *
     * @param retryable 재시도 가능 여부(M13). 연결 수립 실패·429(rate limit)는 true, {@code ok:false}의
     *     인증·권한·채널 오류나 요청 준비 실패는 false다.
     * @param retryAfterMs 429 응답의 {@code Retry-After} 헤더(ms로 환산). 해당 없으면 0 — 호출자는
     *     0이면 재시도 정책의 기본 대기를 쓴다.
     */
    record Failed(String reason, boolean retryable, long retryAfterMs) implements SlackSendResult {
        public Failed(String reason) {
            this(reason, false, 0);
        }
    }

    /** 전송 여부를 확인할 수 없음. 자동 재발신 대상이 아니다(A10). */
    record Unknown(String reason) implements SlackSendResult {}
}
