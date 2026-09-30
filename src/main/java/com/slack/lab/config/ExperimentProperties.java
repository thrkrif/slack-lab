package com.slack.lab.config;

import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 실험 전용 스위치. 운영 기본값은 지연 없음이다. dedupEnabled는 M12에서 EventDeduplicator와 함께 제거했다
// (중복 억제는 이제 워커가 M11 선점 결과표로 한다 — 끌 수 있는 스위치가 아니다).
@Validated
@ConfigurationProperties("experiment")
public record ExperimentProperties(
        @DefaultValue("0") @PositiveOrZero long slowModeMs,
        // M14 복구 실험(R3): 발신 성공 직후 Runtime.halt로 프로세스를 끊어 "보냈는데 기록하지 못한" 상태를 만든다.
        @DefaultValue("false") boolean haltAfterSend) {
}
