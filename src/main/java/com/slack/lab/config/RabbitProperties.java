package com.slack.lab.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

// RabbitMQ 큐 어댑터 설정(ADR-9, M21). 수신 서버의 "저장 확인 대기"는 queue.enqueue-timeout-ms를 그대로 쓴다.
// defer-delay-ms: 지금 처리할 수 없어 놓아준 메시지(다른 시도가 처리 중 등)를 다시 받기까지 기다리는 시간. 임대(30초)보다
//   짧게 잡아 임대 만료를 몇 번 안에 따라잡고, 너무 짧으면 처리 중인 메시지가 빠르게 맴돈다.
// delivery-limit: 소비자가 예외로 계속 nack해도 무한히 맴돌지 않게 데드레터 큐로 보내는 전달 횟수 상한(quorum queue).
@Validated
@ConfigurationProperties("rabbitmq")
public record RabbitProperties(
        @DefaultValue("localhost") @NotBlank String host,
        @DefaultValue("5672") @Positive int port,
        @DefaultValue("guest") @NotBlank String username,
        @DefaultValue("guest") @NotBlank String password,
        @DefaultValue("/") @NotBlank String virtualHost,
        @DefaultValue("slack.events") @NotBlank String eventsQueue,
        @DefaultValue("15000") @Positive long deferDelayMs,
        @DefaultValue("5") @Positive int deliveryLimit) {
}
