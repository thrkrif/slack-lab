package com.slack.lab.state;

/** 복구 전이 결과. {@code status}는 {@code state.lua}가 돌려준 판정 문자열이다. */
public record RecoveryOutcome(String status, String detail) {

    public boolean ok() {
        return "OK".equals(status);
    }

    /** 사람에게 보여줄 거절 사유. */
    public String describe() {
        return switch (status) {
            case "OK" -> "완료";
            case "NOT_FOUND" -> "상태 기록이 없다(event_id 확인)";
            case "BAD_STATE" -> "지금 상태(" + detail + ")에서는 할 수 없다 — UNKNOWN·DEAD 건만 대상이다";
            case "NO_PRESERVED" -> "보존된 입력이 없어 재처리할 수 없다";
            case "CONFLICT" -> "이미 다른 ts(" + detail + ")로 완료 처리돼 있다";
            default -> status;
        };
    }
}
