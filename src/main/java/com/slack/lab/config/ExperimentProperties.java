package com.slack.lab.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 실험 전용 스위치. 운영 기본값은 지연 없음, 중복 억제 켬이다.
@Validated
@ConfigurationProperties("experiment")
public record ExperimentProperties(
        @DefaultValue("0") @PositiveOrZero long slowModeMs,
        @DefaultValue("true") boolean dedupEnabled) {
}
