package com.slack.lab.state;

/** 선점 결과표(PLAN 2단계 M11)의 판정. {@link Settled}는 스크립트가 이미 보존·ACK까지 끝낸 경우다. */
public sealed interface ClaimOutcome {

    /** 처리 권한을 얻었다. 메시지는 아직 pending이며 종료 기록 뒤에 ACK한다. */
    record Claimed(String attemptId, long gen, boolean manualRun) implements ClaimOutcome {}

    /** 다른 시도가 유효한 임대로 처리 중이다. ACK하지 않고 pending에 남긴다(뒤에 XAUTOCLAIM이 재확인). */
    record Busy() implements ClaimOutcome {}

    /** 실행하지 않고 끝냈다. 필요한 보존과 ACK는 이미 적용됐다. */
    record Settled(Reason reason) implements ClaimOutcome {}

    /** 보존해야 하는데 스트림 본문이 없다. 아무것도 쓰지 않았다. */
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
