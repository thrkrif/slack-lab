package com.slack.lab.event;

/**
 * 핸들러가 받는 것은 저장소가 아니라 이 핸들이다. 처리 권한을 가진 시도 하나의 상태 전이만 노출한다.
 * P1에서는 구현체만 공유 저장소 기반으로 바뀐다.
 *
 * 전이가 거절되면(소유자가 바뀌었거나 이미 종료됨) false를 돌려준다. 특히 {@link #markSending()}이 false면
 * 발신하면 안 된다 — SENDING 기록 없이 나간 메시지는 중복 억제가 추적하지 못한다.
 */
public interface AttemptHandle {

    String eventId();

    String attemptId();

    /** 처리 시작(t0)의 단조 시계 값. 기한 계산의 기준이다. */
    long startNanos();

    boolean markSending();

    boolean markCompleted();

    /** 전송되지 않았음이 확실한 실패. PROCESSING·SENDING 어디서든 호출할 수 있다. */
    boolean markFailed(String stage);

    /** 전송 여부를 확인할 수 없음. 자동 재발신 대상이 아니다. */
    boolean markUnknown(String stage);
}
