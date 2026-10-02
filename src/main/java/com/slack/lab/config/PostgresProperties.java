package com.slack.lab.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// 작업 상태 저장소(Postgres) 접속 정보(ADR-9, M22). 비밀번호는 환경변수로 주입한다.
@Validated
@ConfigurationProperties("postgres")
public record PostgresProperties(
        @DefaultValue("jdbc:postgresql://localhost:5432/slacklab") @NotBlank String url,
        @DefaultValue("slacklab") @NotBlank String username,
        @DefaultValue("slacklab") @NotBlank String password,
        @DefaultValue("8") @Positive int poolSize) {
}
