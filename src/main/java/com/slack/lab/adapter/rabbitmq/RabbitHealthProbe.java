package com.slack.lab.adapter.rabbitmq;

import com.slack.lab.core.port.HealthProbe;

/** 브로커에 연결할 수 없으면 수신 서버는 이벤트를 수락할 수 없다 — 503으로 드러낸다. */
public class RabbitHealthProbe implements HealthProbe {

    private final RabbitBroker broker;

    public RabbitHealthProbe(RabbitBroker broker) {
        this.broker = broker;
    }

    @Override
    public String name() {
        return "rabbitmq";
    }

    @Override
    public boolean up() {
        return broker.isUp();
    }
}
