package com.slack.lab.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 작업 상태 저장소(Postgres) 접속 정보(ADR-9, M22). 비밀번호는 기본값을 두지 않고 환경변수(POSTGRES_PASSWORD)로 주입한다.
// 수신처럼 DB에 연결하지 않는 역할은 비워 둬도 되므로 여기서 필수로 검증하지 않고, 연결을 만드는 쪽에서 검사한다.
@Validated
@ConfigurationProperties("postgres")
public record PostgresProperties(
        @DefaultValue("jdbc:postgresql://localhost:5432/slacklab") @NotBlank String url,
        @DefaultValue("slacklab") @NotBlank String username,
        @DefaultValue("") String password,
        @DefaultValue("8") @Positive int poolSize) {
}
