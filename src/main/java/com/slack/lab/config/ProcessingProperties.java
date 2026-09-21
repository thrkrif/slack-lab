package com.slack.lab.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 처리 상한 60초는 호출별 기한의 합으로 강제한다 (PRD §5, 규칙 10)
@Validated
@ConfigurationProperties("processing")
public record ProcessingProperties(
        @DefaultValue("60000") @Positive long totalDeadlineMs) {
}
