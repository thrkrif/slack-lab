package com.slack.lab.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// enqueue-timeout-ms: 발행 확인(publisher confirm) 대기 상한. 수신 p95 200ms 예산 안에서 넉넉히 잡는다(M21 실측 §19).
@Validated
@ConfigurationProperties("queue")
public record QueueProperties(@DefaultValue("150") @Positive long enqueueTimeoutMs) {
}
