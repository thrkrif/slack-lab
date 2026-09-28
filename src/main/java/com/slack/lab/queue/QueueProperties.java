package com.slack.lab.queue;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// enqueue-timeout-ms: M9 실측 XADD+WAITAOF p95 3.18ms(EXPERIMENT-LOG §6.1). 수신 p95 200ms 예산 안에서 넉넉히 잡는다.
// claim-min-idle-ms: 정상 처리 중인 메시지를 회수하지 않도록 총 기한 + 임대 이상이어야 한다(기동 시 검증).
@Validated
@ConfigurationProperties("queue")
public record QueueProperties(
        @DefaultValue("slack:events") @NotBlank String streamKey,
        @DefaultValue("workers") @NotBlank String group,
        @DefaultValue("150") @Positive long enqueueTimeoutMs,
        @DefaultValue("100000") @Positive long claimMinIdleMs) {
}
