package com.slack.lab.state;

/**
 * 한 시도의 종료 기록. 워커가 핸들러 결과를 보고 만든다.
 *
 * <p>정적 팩토리가 만드는 {@code state}·{@code preserveTo} 조합만 유효하다. 정본 생성자에서 그 조합을 검증하는
 * 건 {@code UNKNOWN}을 목록 보존 없이({@code NONE}) 기록하는 등 스크립트의 상태별 종료 계약(M11)과 어긋나는
 * 조합이 (레코드라 생성자를 감출 수 없으므로) 직접 만들어져도 즉시 걸러지게 하기 위해서다.
 *
 * @param preserveTo 입력을 보존할 목록. 결과 불명은 복구 목록, 명확한 실패는 DLQ, 완료는 없음
 */
public record Finalization(State state, Destination preserveTo, String slackTs, String kind, String stage) {

    public enum State { COMPLETED, UNKNOWN, DEAD }

    public enum Destination { NONE, DLQ, RECOVERY }

    public Finalization {
        Destination expected = switch (state) {
            case COMPLETED -> Destination.NONE;
            case UNKNOWN -> Destination.RECOVERY;
            case DEAD -> Destination.DLQ;
        };
        if (preserveTo != expected) {
            throw new IllegalArgumentException(state + "는 " + expected + "로만 보존한다 (받음: " + preserveTo + ")");
        }
    }

    public static Finalization completed(String slackTs, String kind) {
        return new Finalization(State.COMPLETED, Destination.NONE, slackTs, kind, "delivered");
    }

    public static Finalization unknown(String kind, String stage) {
        return new Finalization(State.UNKNOWN, Destination.RECOVERY, "", kind, stage);
    }

    public static Finalization dead(String kind, String stage) {
        return new Finalization(State.DEAD, Destination.DLQ, "", kind, stage);
    }
}
