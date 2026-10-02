package com.slack.lab.adapter.rabbitmq;

import com.rabbitmq.client.Channel;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.QueueDelivery;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * RabbitMQ 메시지 하나를 코어의 {@link QueueDelivery}로 감싼다. 한 번만 확정할 수 있다: 같은 delivery tag를 두 번 ack하면
 * 브로커가 채널을 닫는다. 자동 복구된 연결의 채널은 복구 전에 받은 tag에 대한 ack를 조용히 무시한다 — 그 메시지는
 * 브로커가 이미 되돌려 다시 전달하고, 선점 결과표가 중복을 흡수한다.
 */
final class RabbitDelivery implements QueueDelivery {

    private final Channel channel;
    private final long deliveryTag;
    private final byte[] body;
    private final EventMessageJson.Parsed parsed;
    private final RabbitEventPublisher publisher;
    private final AtomicBoolean settled = new AtomicBoolean();

    RabbitDelivery(Channel channel, long deliveryTag, byte[] body, EventMessageJson.Parsed parsed,
            RabbitEventPublisher publisher) {
        this.channel = channel;
        this.deliveryTag = deliveryTag;
        this.body = body;
        this.parsed = parsed;
        this.publisher = publisher;
    }

    @Override
    public SlackMessageEvent event() {
        return parsed.event();
    }

    @Override
    public long gen() {
        return parsed.gen();
    }

    @Override
    public long receivedAtMs() {
        return parsed.receivedAtMs();
    }

    @Override
    public boolean receivedAtMissing() {
        return parsed.receivedAtMissing();
    }

    @Override
    public String token() {
        return Long.toString(deliveryTag);
    }

    boolean settled() {
        return settled.get();
    }

    @Override
    public void acknowledge() {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        try {
            channel.basicAck(deliveryTag, false);
        } catch (IOException | RuntimeException e) {
            // 채널이 이미 닫혔다 — 브로커가 다시 전달하고, 선점 결과표가 종료로 판정해 다시 확정한다.
            throw new IllegalStateException("ack 실패", e);
        }
    }

    @Override
    public void defer() {
        if (!settled.compareAndSet(false, true)) {
            return;
        }
        // 지연 큐에 복사본을 넣고(확인 대기) 원본을 ack한다. 확인 전에 죽으면 원본이 unacked로 남아 브로커가 다시 전달한다
        // (중복은 선점 결과표가 흡수한다). 즉시 nack(requeue)하면 처리 중인 메시지가 빠르게 맴돈다.
        boolean copied = publisher.publishDeferred(body, parsed.event().eventId(), parsed.gen());
        try {
            if (copied) {
                channel.basicAck(deliveryTag, false);
            } else {
                // 지연 큐에도 넣지 못했다 — 즉시 재전달에 맡긴다(전달 횟수 상한이 무한 맴돌기를 막는다).
                channel.basicNack(deliveryTag, false, true);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("defer 실패", e);
        }
    }

    /** 처리 중 예외로 확정하지 못했을 때: 재전달에 맡긴다. 이미 확정했으면 아무것도 하지 않는다. */
    void nackQuietly() {
        if (settled.compareAndSet(false, true)) {
            try {
                channel.basicNack(deliveryTag, false, true);
            } catch (IOException | RuntimeException ignored) {
                // 채널이 닫혔으면 브로커가 이미 되돌렸다
            }
        }
    }
}
