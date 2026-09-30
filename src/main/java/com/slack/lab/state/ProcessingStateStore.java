package com.slack.lab.state;

/**
 * 프로세스 밖 공유 처리 상태(P1-3·P1-8). P0 인메모리 {@code EventDeduplicator}를 대체한다.
 * 모든 전이는 소유자(attempt_id)·상태·세대·임대를 한 번에 확인하는 조건부 갱신이다.
 */
public interface ProcessingStateStore {

    ClaimOutcome claim(ClaimRequest request);

    /** 임대가 유효한 소유자만 SENDING으로 전이한다. false면 발신하면 안 된다. */
    boolean markSending(String eventId, String attemptId);

    /** 임대를 연장한다. 이미 만료됐으면 되살리지 않고 false다. */
    boolean renew(String eventId, String attemptId);

    /**
     * 종료 상태를 기록하고 메시지를 ACK·삭제한다. 거절되면(소유권 상실·본문 없음) 아무것도 쓰지 않고 false다 —
     * 호출자는 ACK하지 않는다.
     */
    boolean finalizeAttempt(String eventId, String attemptId, String streamId, Finalization finalization);

    /**
     * 재시도 가능한 오류를 안내 없이 예약한다(M13). 입력을 재시도 목록에 {@code retryAtMs}로 보존하고
     * {@code RETRY_WAIT(nextGen, retries)}로 기록한 뒤 메시지를 ACK한다. finalizeAttempt와 같은 거절 규약 —
     * 소유권 상실·본문 없음이면 아무것도 쓰지 않고 false다.
     *
     * @param nextGen 재투입할 메시지의 세대(현재 gen + 1)
     * @param retryAtMs 재시도 스케줄러가 재투입할 절대 시각(epoch ms)
     * @param retries 이번 예약까지 포함한 누적 재시도 횟수
     */
    boolean scheduleRetry(String eventId, String attemptId, String streamId, long nextGen, long retryAtMs,
            int retries, String stage);
}
