package com.slack.lab.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// ARCHITECTURE §3.2 P1 초기 정책: 임대 30초·10초마다 갱신, 완료 7일 보존, 자동 실행 24시간.
@Validated
@ConfigurationProperties("state")
public record StateProperties(
        @DefaultValue("30000") @Positive long leaseMs,
        @DefaultValue("10000") @Positive long renewMs,
        @DefaultValue("7") @Positive int completedRetentionDays,
        @DefaultValue("24") @Positive int executionWindowHours) {
}
