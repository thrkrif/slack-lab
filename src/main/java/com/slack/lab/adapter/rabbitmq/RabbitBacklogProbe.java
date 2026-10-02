package com.slack.lab.adapter.rabbitmq;

import com.rabbitmq.client.Channel;
import com.slack.lab.core.port.BacklogProbe;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/** 처리 큐·지연 큐·죽은 편지 큐의 대기 건수. 수동 확인 전(unacked) 건수는 관리 API가 필요해 재지 않는다. */
public class RabbitBacklogProbe implements BacklogProbe {

    private final RabbitBroker broker;

    public RabbitBacklogProbe(RabbitBroker broker) {
        this.broker = broker;
    }

    @Override
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        // 수동 선언(passive)은 큐가 없으면 채널을 닫아 버린다 — 큐마다 새 채널을 쓴다.
        out.put("queue_ready", count(broker.eventsQueue()));
        out.put("defer", count(broker.deferQueue()));
        out.put("dead", count(broker.deadQueue()));
        return out;
    }

    private long count(String queue) {
        try (Channel ch = broker.newChannel()) {
            return ch.queueDeclarePassive(queue).getMessageCount();
        } catch (IOException | TimeoutException e) {
            throw new IllegalStateException("queue_depth_unavailable", e);
        }
    }
}
