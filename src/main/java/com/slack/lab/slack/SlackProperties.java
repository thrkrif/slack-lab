package com.slack.lab.slack;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 비밀값이 비어 있으면 서명 검증이 무력화되므로 기동 단계에서 막는다.
@Validated
@ConfigurationProperties("slack")
public record SlackProperties(
        @NotBlank String signingSecret,
        @NotBlank String botToken,
        // 값을 바꾸면 A10 실험에서 끊김 스텁으로 향하게 할 수 있다
        @DefaultValue("https://slack.com/api") @NotBlank String baseUrl,
        @DefaultValue("10000") @Positive long sendDeadlineMs) {
}
