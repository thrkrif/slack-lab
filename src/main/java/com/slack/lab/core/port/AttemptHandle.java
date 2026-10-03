package com.slack.lab.core.port;

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

    /**
     * 이번 시도가 내보내려는 메시지 종류를 알린다. 발신 이후 예상 못한 예외가 나도 어떤 메시지였는지 기록할 수 있게
     * {@link #markSending()} 전에 부른다. 기록하지 않는 구현체는 무시해도 된다.
     */
    default void recordKind(com.slack.lab.core.model.MessageKind kind) {}

    /**
     * 단계별 소요(ms)를 워커에 알린다(M17 관측: {@code llm_ms}·{@code send_ms} 등). 워커가 시도 하나의 지표를 한 줄로
     * 묶어 남기므로 핸들러는 값을 로그에 흩뿌리지 않는다. 기본 구현은 아무것도 하지 않는다.
     */
    default void recordPhase(String name, long millis) {}
}
