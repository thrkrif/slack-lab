package com.slack.lab.worker;

import com.slack.lab.event.AttemptHandle;
import com.slack.lab.state.ProcessingStateStore;

/**
 * {@link AttemptHandle}의 M12 구현. 저장소를 직접 쥐지만 핸들러에게는 인터페이스로만 보인다(규칙 2).
 * {@link #sendingMarked()}는 워커의 예외 가드가 Failed(발신 전)와 Unknown(발신 이후 불명)을 가르는 데 쓴다.
 */
final class WorkerAttemptHandle implements AttemptHandle {

    private final String eventId;
    private final String attemptId;
    private final long startNanos;
    private final ProcessingStateStore store;
    private volatile boolean sendingMarked;

    WorkerAttemptHandle(String eventId, String attemptId, long startNanos, ProcessingStateStore store) {
        this.eventId = eventId;
        this.attemptId = attemptId;
        this.startNanos = startNanos;
        this.store = store;
    }

    @Override
    public String eventId() {
        return eventId;
    }

    @Override
    public String attemptId() {
        return attemptId;
    }

    @Override
    public long startNanos() {
        return startNanos;
    }

    @Override
    public boolean markSending() {
        boolean ok = store.markSending(eventId, attemptId);
        if (ok) {
            sendingMarked = true;
        }
        return ok;
    }

    boolean sendingMarked() {
        return sendingMarked;
    }
}
