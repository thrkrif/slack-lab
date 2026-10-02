package com.slack.lab.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 스레드 문맥 한도(M16). fetch-deadline-ms는 LLM 단계 50초 예산에 포함된다.
@Validated
@ConfigurationProperties("context")
public record ContextProperties(
        @DefaultValue("10") @Positive int maxMessages,
        @DefaultValue("4000") @Positive int maxChars,
        @DefaultValue("3000") @Positive long fetchDeadlineMs) {
}
