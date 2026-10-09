package com.slack.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 4단계 요청 분류 설정. {@code enabled=false}(기본)이면 분류기 빈을 만들지 않아 3단계 흐름이 그대로다. 분류 모델은
 * {@code llm.base-url}을 공유하고 모델 ID만 따로 받는다 — 로컬 Ollama 안의 다중 모델까지가 범위라 별도 base-url(= 외부 벤더)은
 * 구조적으로 만들 수 없다(PRD 4단계 범위). 코어는 이 설정을 읽지 않는다(어댑터·기동 검사 전용).
 *
 * <p>모델 ID는 기본값이 없다(추측 금지, {@code ollama list}). 같은 모델을 답변과 함께 쓰면(16GB 노트북의 기본 구성, M31) {@code
 * keepAlive}는 {@code llm.keep-alive}를 상속해 같은 모델에 두 값이 경합하지 않는다.
 */
@ConfigurationProperties("classification")
public record ClassificationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("") String model,
        // 분류 한 번에 쓸 수 있는 상한. 50초 LLM 예산에서 쓰고, 넘으면 장애 질문으로 폴백한다.
        @DefaultValue("5000") long timeoutMs,
        @DefaultValue("30m") String keepAlive,
        @DefaultValue("true") boolean verifyOnStartup) {
}
