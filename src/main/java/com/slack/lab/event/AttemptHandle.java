package com.slack.lab.event;

/**
 * 핸들러가 시도 하나에 대해 갖는 유일한 권한: 발신 직전 SENDING 게이트. M12부터 종료 상태 기록
 * ({@code markCompleted}/{@code markFailed}/{@code markUnknown})은 워커가 {@code ProcessingStateStore}로
 * 직접 하고, 핸들러는 결과({@link HandlingResult})만 돌려준다 — 저장소를 몰라도 되게 하기 위해서다.
 *
 * {@link #markSending()}이 false면 발신하면 안 된다 — SENDING 기록 없이 나간 메시지는 중복 억제가
 * 추적하지 못한다.
 */
public interface AttemptHandle {

    String eventId();

    String attemptId();

    /** 처리 시작(t0)의 단조 시계 값. 기한 계산의 기준이다. */
    long startNanos();

    boolean markSending();
}
