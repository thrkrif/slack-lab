package com.slack.lab.adapter.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.slack.lab.core.service.EventProcessor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RabbitMQ 소비 어댑터(M21). 채널마다 prefetch 1로 한 번에 한 메시지만 받는다(LLM 동시성 1, 소비자 {@code concurrency}개).
 * 받은 메시지는 {@link EventProcessor}에 넘기고, 처리·상태 기록·확정은 코어의 몫이다. 코어가 확정(ack)도 놓아주기(defer)도
 * 하지 않고 돌아오면 안전망으로 재전달에 맡긴다. 처리 중 예외는 nack(requeue)하고, 전달 횟수가 상한을 넘으면 브로커가
 * 데드레터 큐로 보낸다.
 *
 * <p>소비자 타임아웃: RabbitMQ 기본 {@code consumer_timeout}은 30분이라 LLM 처리 최대 50초보다 훨씬 길다. 이 값을
 * 줄이는 설정은 처리 시간(총 60초)보다 길게 유지해야 한다.
 */
public class RabbitConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitConsumer.class);

    private final RabbitBroker broker;
    private final RabbitEventPublisher publisher;
    private final EventProcessor processor;
    private final ObjectMapper mapper;
    private final int concurrency;
    private final List<Channel> channels = new ArrayList<>();

    public RabbitConsumer(RabbitBroker broker, RabbitEventPublisher publisher, EventProcessor processor,
            ObjectMapper mapper, int concurrency) {
        this.broker = broker;
        this.publisher = publisher;
        this.processor = processor;
        this.mapper = mapper;
        this.concurrency = concurrency;
    }

    public synchronized void start() throws IOException, TimeoutException {
        for (int i = 0; i < concurrency; i++) {
            Channel channel = broker.newChannel();
            channel.basicQos(1);
            String tag = channel.basicConsume(broker.eventsQueue(), false, new Handler(channel));
            channels.add(channel);
            log.info("워커 소비자 시작 consumer={} queue={}", tag, broker.eventsQueue());
        }
    }

    private final class Handler extends DefaultConsumer {

        Handler(Channel channel) {
            super(channel);
        }

        @Override
        public void handleDelivery(String consumerTag, Envelope envelope, AMQP.BasicProperties properties,
                byte[] body) {
            Channel ch = getChannel();
            EventMessageJson.Parsed parsed = EventMessageJson.read(mapper, body);
            if (parsed == null) {
                // 읽을 수 없는 메시지는 다시 줘도 소용없다 — requeue 없이 거절해 데드레터 큐로 보낸다.
                log.error("읽을 수 없는 큐 메시지 — 데드레터 큐로 보냄 delivery_tag={}", envelope.getDeliveryTag());
                try {
                    ch.basicNack(envelope.getDeliveryTag(), false, false);
                } catch (IOException | RuntimeException e) {
                    log.warn("독약 메시지 nack 실패 reason={}", e.getClass().getSimpleName());
                }
                return;
            }
            RabbitDelivery delivery = new RabbitDelivery(ch, envelope.getDeliveryTag(), body, parsed, publisher);
            try {
                processor.process(delivery);
            } catch (Throwable t) {
                log.error("메시지 처리 중 예외 — 재전달에 맡김 event_id={}", parsed.event().eventId(), t);
                delivery.nackQuietly();
                return;
            }
            if (!delivery.settled()) {
                // 코어가 확정도 놓아주기도 하지 않았다(포트 계약 위반) — 메시지가 prefetch 슬롯을 영원히 쥐지 않게 한다.
                log.warn("확정되지 않은 메시지 — 재전달에 맡김 event_id={}", parsed.event().eventId());
                delivery.nackQuietly();
            }
        }
    }

    @Override
    public synchronized void close() {
        for (Channel ch : channels) {
            try {
                if (ch.isOpen()) {
                    ch.close();
                }
            } catch (IOException | TimeoutException | RuntimeException ignored) {
                // 종료 중 오류는 무시한다
            }
        }
        channels.clear();
    }
}
