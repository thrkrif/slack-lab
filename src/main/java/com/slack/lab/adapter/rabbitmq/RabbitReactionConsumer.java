package com.slack.lab.adapter.rabbitmq;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.slack.lab.config.ReactionProperties;
import com.slack.lab.core.model.ReactionResult;
import com.slack.lab.core.port.ChatNotifier;
import java.io.IOException;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 즉시 반응(M15, RabbitMQ 버전). 반응 큐의 최소 항목을 읽어 원 메시지에 이모지를 붙인다. 처리 큐와 독립이라 LLM이 밀려
 * 있어도 반응은 빠르다. 보조 기능이라 실패해도 재시도하지 않고 로그만 남긴 뒤 항목을 지운다. 소비자가 죽으면 브로커가 다시
 * 전달한다(이미 붙은 반응은 {@code already_reacted}라 성공으로 본다).
 */
public class RabbitReactionConsumer implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RabbitReactionConsumer.class);

    private final RabbitBroker broker;
    private final ObjectMapper mapper;
    private final ChatNotifier notifier;
    private final ReactionProperties props;
    private Channel channel;

    public RabbitReactionConsumer(RabbitBroker broker, ObjectMapper mapper, ChatNotifier notifier,
            ReactionProperties props) {
        this.broker = broker;
        this.mapper = mapper;
        this.notifier = notifier;
        this.props = props;
    }

    public synchronized void start() throws IOException, TimeoutException {
        channel = broker.newChannel();
        channel.basicQos(5);
        channel.basicConsume(broker.reactionsQueue(), false, new DefaultConsumer(channel) {
            @Override
            public void handleDelivery(String tag, Envelope envelope, AMQP.BasicProperties properties, byte[] body) {
                handle(getChannel(), envelope.getDeliveryTag(), body);
            }
        });
        log.info("반응 소비자 시작 queue={} emoji={}", broker.reactionsQueue(), props.emoji());
    }

    void handle(Channel ch, long deliveryTag, byte[] body) {
        try {
            JsonNode n = mapper.readTree(body);
            String eventId = n.path("event_id").asText("");
            long receivedAt = n.path("received_at").asLong(0);
            if (props.experimentDelayMs() > 0) {
                Thread.sleep(props.experimentDelayMs());
            }
            ReactionResult result = notifier.addReaction(n.path("channel").asText(""), n.path("ts").asText(""),
                    props.emoji());
            if (result.ok()) {
                log.info("반응 성공 event_id={} reaction_ms={} detail={}", eventId,
                        receivedAt > 0 ? System.currentTimeMillis() - receivedAt : -1, result.detail());
            } else {
                // 보조 기능이라 재시도하지 않는다. 답글 처리에는 영향이 없다.
                log.warn("반응 실패(재시도 안 함) event_id={} reason={}", eventId, result.detail());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return; // 확정하지 않는다 — 브로커가 다시 전달한다
        } catch (Exception e) {
            log.warn("반응 항목 처리 오류(버림) reason={}", e.getClass().getSimpleName());
        }
        try {
            ch.basicAck(deliveryTag, false);
        } catch (IOException | RuntimeException e) {
            log.warn("반응 ack 실패 reason={}", e.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
        } catch (IOException | TimeoutException | RuntimeException ignored) {
            // 종료 중 오류는 무시한다
        }
    }
}
