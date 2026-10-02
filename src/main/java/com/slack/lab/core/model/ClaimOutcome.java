package com.slack.lab.core.model;

/**
 * 선점 결과표(PLAN 2단계 M11)의 판정. 구현체가 어떤 저장소인지와 무관한 의미만 적는다.
 * {@link Settled}를 돌려주기 전에 필요한 입력 보존은 <b>커밋까지 끝나 있어야 한다</b> — 코어가 곧바로 메시지를 확정(ACK)한다.
 */
public sealed interface ClaimOutcome {

    /**
     * 처리 권한을 얻었다. 메시지는 종료 기록이 확인될 때까지 확정하지 않는다.
     *
     * @param retries 지금까지 소진한 재시도 횟수(M13). 최초 시도는 0. {@code retries >= retry.max-retries}면
     *     이번이 마지막 시도다(워커가 {@code finalAttempt}를 계산하는 데 쓴다).
     */
    record Claimed(String attemptId, long gen, boolean manualRun, int retries) implements ClaimOutcome {}

    /** 다른 시도가 유효한 임대로 처리 중이다. 확정하지 않고 나중에 다시 확인하게 놓아준다(defer). */
    record Busy() implements ClaimOutcome {}

    /** 실행하지 않고 끝냈다. 필요한 보존은 이미 커밋됐고, 코어가 메시지를 확정한다. */
    record Settled(Reason reason) implements ClaimOutcome {}

    /** 보존해야 하는데 보존할 입력이 없다(메시지 본문 소실, 또는 입력 없이 호출됨). 아무것도 쓰지 않았다. */
    record NoInput() implements ClaimOutcome {}

    enum Reason {
        /** 이미 완료·종료된 이벤트 */
        DONE,
        /** 이전 세대이거나 예약된 재시도보다 이른 메시지 */
        STALE,
        /** 발신 중 임대가 만료돼 결과 불명으로 전환 */
        UNKNOWN,
        /** 승인된 수동 실행이 소실돼 종료 */
        DEAD,
        /** 자동 실행 허용 기간 초과 */
        EXPIRED,
        /** 상태보다 큰 세대 — 불변식 위반 */
        ANOMALY
    }
}
