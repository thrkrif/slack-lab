package com.slack.lab.state;

/**
 * 큐에서 꺼낸 메시지 하나에 대한 선점 요청.
 *
 * @param streamId 스트림 항목 ID. 보존·ACK 대상이다
 * @param gen 메시지 세대. 최초 수신은 0이고 재시도·재처리 투입마다 커진다
 * @param receivedAtMs 최초 서버 수신 시각(UTC epoch ms). 재투입해도 바뀌지 않는다
 */
public record ClaimRequest(String eventId, String streamId, long gen, long receivedAtMs, String channel,
        String threadTs) {
}
