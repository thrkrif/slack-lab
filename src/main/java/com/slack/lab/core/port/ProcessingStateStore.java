package com.slack.lab.core.port;

import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;

/**
 * 프로세스 밖 공유 처리 상태(P1-3·P1-8). P0 인메모리 {@code EventDeduplicator}를 대체한다.
 * 모든 전이는 소유자(attempt_id)·상태·세대·임대를 한 번에 확인하는 조건부 갱신이다.
 *
 * <p><b>이 포트의 계약</b>: 상태를 기록하고 {@code true}/{@code false}로 확정 여부만 알린다. 메시지를 큐에서 제거(ACK)하는
 * 책임은 호출자(코어)가 {@code QueueDelivery.acknowledge()}로 진다. 결과 불명·DLQ·재시도처럼 입력을 보존해야 하는 전이는
 * 선점 때 받은 {@code ClaimRequest.input}을 저장소가 보관했다가 쓴다. Redis 구현은 메시지(스트림 항목)가 곧 입력 원본이고
 * 같은 Lua 스크립트로 ACK까지 원자적으로 하므로 이 계약을 더 강하게 만족할 뿐이다 — 코어는 그것에 기대지 않는다.
 * {@code deliveryToken}은 그런 구현이 쓰는 불투명 값이며 Postgres 같은 구현은 무시해도 된다.
 */
public interface ProcessingStateStore {

    /**
     * 선점 결과표대로 판정한다. {@code Settled}를 돌려주기 전에 필요한 입력 보존(DLQ·복구·재시도 예약)은 커밋까지 끝나
     * 있어야 한다 — 코어가 곧바로 메시지를 확정하기 때문이다. 보존이 필요한데 입력이 없으면(Postgres라면
     * {@code request.input() == null}) 아무것도 쓰지 않고 {@code NoInput}을 돌려준다.
     */
    ClaimOutcome claim(ClaimRequest request);

    /** 임대가 유효한 소유자만 SENDING으로 전이한다. false면 발신하면 안 된다. */
    boolean markSending(String eventId, String attemptId);

    /** 임대를 연장한다. 이미 만료됐으면 되살리지 않고 false다. */
    boolean renew(String eventId, String attemptId);

    /**
     * 종료 상태를 기록한다(입력 보존이 필요한 상태는 보존까지). 거절되면(소유권 상실·보존할 입력 없음) 아무것도 쓰지 않고
     * false다 — 호출자는 ACK하지 않는다.
     */
    boolean finalizeAttempt(String eventId, String attemptId, String deliveryToken, Finalization finalization);

    /**
     * 재시도 가능한 오류를 안내 없이 예약한다(M13). 입력을 {@code retryAtMs}에 재투입하도록 보존하고
     * {@code RETRY_WAIT(nextGen, retries)}로 기록한다. finalizeAttempt와 같은 거절 규약 — 소유권 상실·보존할 입력
     * 없음이면 아무것도 쓰지 않고 false다.
     *
     * <p>M21 예정: 브로커가 지연 재발행을 직접 지원하므로(RabbitMQ) 예약·재투입은 큐 포트로 옮기고, 이 메서드는 상태 전이만
     * 남는다. 지금은 Redis 구현이 예약 목록과 재투입 스케줄러를 함께 맡는다.
     *
     * @param nextGen 재투입할 메시지의 세대(현재 gen + 1)
     * @param retryAtMs 재시도 스케줄러가 재투입할 절대 시각(epoch ms)
     * @param retries 이번 예약까지 포함한 누적 재시도 횟수
     */
    boolean scheduleRetry(String eventId, String attemptId, String deliveryToken, long nextGen, long retryAtMs,
            int retries, String stage);
}
