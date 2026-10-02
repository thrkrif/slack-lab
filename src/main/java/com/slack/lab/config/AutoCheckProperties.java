package com.slack.lab.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 결과 불명 건의 읽기 전용 자동 조회(ADR-9 6항). 발신 직후에는 Slack 조회에 아직 반영되지 않을 수 있어 min-age-ms만큼 기다린다.
// 조회로 답글을 찾으면 완료 처리하고, 못 찾으면 결과 불명을 그대로 둔다 — 자동 재발신은 하지 않는다(규칙 11).
@Validated
@ConfigurationProperties("recovery.auto-check")
public record AutoCheckProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("10000") @Positive long minAgeMs,
        @DefaultValue("30000") @Positive long intervalMs) {
}
