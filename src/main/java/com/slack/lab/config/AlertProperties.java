package com.slack.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 알람 직접 수신(M23). 시크릿이나 채널이 비어 있으면 엔드포인트는 꺼진다(404) — 인증 없는 공개 입구를 만들지 않는다.
 * 시크릿은 환경변수 {@code ALERT_SECRET}으로만 주입한다. 수신 서버가 아니면 쓰이지 않으므로 필수로 검증하지 않는다.
 */
@ConfigurationProperties("alert")
public record AlertProperties(@DefaultValue("") String secret, @DefaultValue("") String channel) {

    public boolean enabled() {
        return !secret.isBlank() && !channel.isBlank();
    }
}
