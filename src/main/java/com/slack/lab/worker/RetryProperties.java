package com.slack.lab.worker;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// ARCHITECTURE §5.1 초기 재시도 정책: 최초 시도 이후 최대 3회, 대기 5초·30초·120초.
@Validated
@ConfigurationProperties("retry")
public record RetryProperties(
        @DefaultValue({"5000", "30000", "120000"}) @NotEmpty List<@NotNull @Positive Long> backoffMs,
        @DefaultValue("3") @PositiveOrZero int maxRetries) {
}
