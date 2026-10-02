package com.slack.lab.core.model;

import java.util.Map;

/**
 * 모니터링 알람 한 건을 코어가 아는 모양으로 정규화한 것(5단계, PLAN M23에서 구체화 — 지금은 자리만 잡았다).
 *
 * @param dedupKey "같은 알람"의 기준: 장애 식별자 + 발생 회차(ADR-9). 같은 회차의 반복 전송은 같은 작업이다
 * @param channel 리포트를 달 채팅 채널
 * @param threadTs 리포트를 달 스레드(알람 메시지의 ts). 없으면 새로 시작한다
 * @param attributes 서비스명·심각도·지표 같은 원천별 부가 정보
 */
public record AlertEvent(String source, String dedupKey, String title, String text, String channel, String threadTs,
        Map<String, String> attributes) {}
