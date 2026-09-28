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
    private volatile boolean ownershipLost;

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
        if (ownershipLost) {
            // 임대 갱신이 이미 실패했다 — 저장소를 다시 부르지 않아도 거절될 것이 확실하다(ARCHITECTURE §3.2
            // "갱신 실패 시 새 발신 금지"). 불필요한 Redis 호출을 줄인다.
            return false;
        }
        boolean ok = store.markSending(eventId, attemptId);
        if (ok) {
            sendingMarked = true;
        }
        return ok;
    }

    boolean sendingMarked() {
        return sendingMarked;
    }

    /** 백그라운드 임대 갱신이 실패했을 때(소유권 상실) 워커가 호출한다. */
    void markOwnershipLost() {
        ownershipLost = true;
    }
}
