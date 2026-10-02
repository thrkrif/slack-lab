package com.slack.lab.core.model;

/**
 * 큐에서 꺼낸 메시지 하나에 대한 선점 요청.
 *
 * @param deliveryToken 브로커가 정한 불투명 전달 식별자(Redis: 스트림 항목 ID). 어댑터 구현이 보존·확인 처리에 쓴다
 * @param gen 메시지 세대. 최초 수신은 0이고 재시도·재처리 투입마다 커진다
 * @param receivedAtMs 최초 서버 수신 시각(UTC epoch ms). 재투입해도 바뀌지 않는다
 * @param input 이벤트 입력 전체. 결과 불명·DLQ·재시도 때 입력을 보존해야 하는 저장소(Postgres 등)가 선점 시점에 받아 둔다.
 *     메시지가 곧 입력 원본인 브로커 어댑터(Redis 스트림)는 쓰지 않을 수 있다. null이면 channel·threadTs만 안다
 */
public record ClaimRequest(String eventId, String deliveryToken, long gen, long receivedAtMs, String channel,
        String threadTs, SlackMessageEvent input) {

    /** 입력 전체 없이 만든다(테스트·입력을 직접 읽는 어댑터용). */
    public ClaimRequest(String eventId, String deliveryToken, long gen, long receivedAtMs, String channel,
            String threadTs) {
        this(eventId, deliveryToken, gen, receivedAtMs, channel, threadTs, null);
    }
}
