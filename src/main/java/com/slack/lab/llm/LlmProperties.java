package com.slack.lab.llm;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 벤더는 코드에 박지 않는다. OpenAI 호환 엔드포인트(baseUrl)와 모델 ID만 설정으로 받는다.
@Validated
@ConfigurationProperties("llm")
public record LlmProperties(
        @DefaultValue("ollama") Client client,
        @NotBlank String baseUrl,
        // 모델 ID는 추측하지 않고 `ollama list` 결과를 환경변수로 준다
        @NotBlank String model,
        @DefaultValue("512") @Positive int maxTokens,
        @DefaultValue("30m") @NotBlank String keepAlive,
        @DefaultValue("50000") @Positive long deadlineMs,
        @DefaultValue("3000") @Positive long connectTimeoutMs,
        @DefaultValue("true") boolean verifyModelOnStartup) {

    // 구현체 선택은 M4에서 @Bean 메서드 안에서 분기한다
    public enum Client {
        OLLAMA, ECHO
    }
}
