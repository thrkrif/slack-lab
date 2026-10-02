package com.slack.lab.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 반응은 보조 기능이다(재시도 없음). 처리 스트림과 분리해 답변이 먼저 끝나 XDEL돼도 반응이 누락되지 않는다.
// reclaim-min-idle-ms: 죽은 반응 소비자의 항목을 짧게 회수한다(B12 p95 ≤ 3s 목표에 맞춰 10초).
// experiment-delay-ms: 실험 전용. 반응 소비자를 지연시켜 "답변이 먼저 끝나는" 상황을 만든다.
@Validated
@ConfigurationProperties("reaction")
public record ReactionProperties(
        @DefaultValue("slack:reactions") @NotBlank String streamKey,
        @DefaultValue("reactors") @NotBlank String group,
        @DefaultValue("eyes") @NotBlank String emoji,
        @DefaultValue("10000") @Positive long reclaimMinIdleMs,
        @DefaultValue("0") @PositiveOrZero long experimentDelayMs) {
}
