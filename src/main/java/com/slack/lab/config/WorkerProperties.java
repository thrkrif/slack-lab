package com.slack.lab.config;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// LLM 동시성 1(M9): 이 장비에서 동시 호출은 처리량을 늘리지 못하고 개별 지연만 키워 시도당 50초 예산을 넘긴다
// (EXPERIMENT-LOG §6.2). 값을 바꾸면 성능 검증 환경이 달라진다(PRD §5).
@Validated
@ConfigurationProperties("worker")
public record WorkerProperties(@DefaultValue("1") @Positive int concurrency) {
}
