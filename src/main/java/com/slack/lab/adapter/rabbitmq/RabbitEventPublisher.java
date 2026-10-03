package com.slack.lab.adapter.rabbitmq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.slack.lab.config.QueueProperties;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.EventPublisher;
import com.slack.lab.core.port.EventRepublisher;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 수신 이벤트를 RabbitMQ에 내구성 있게 저장한다(P1-1). 영속 메시지 + mandatory + 퍼블리셔 컨펌: 브로커가 큐에 안전하게
 * 저장했다고 확인(ack)한 경우에만 {@code Enqueued}를 돌려주고, 그때만 수신 서버가 200을 준다. 확인 대기는
 * {@code queue.enqueue-timeout-ms}를 넘기지 않는다(수신 p95 200ms 안에서의 대기).
 *
 * <p>채널은 스레드 안전하지 않아 발행을 직렬화한다. 이 규모(알람·질문 몇 건)에서는 충분하며, 처리량이 문제가 되면 채널 풀로
 * 바꾼다.
 */
public class RabbitEventPublisher implements EventPublisher, EventRepublisher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEventPublisher.class);

    private final RabbitBroker broker;
    private final ObjectMapper mapper;
    private final long confirmTimeoutMs;
    private Channel channel;
    private final AtomicBoolean returned = new AtomicBoolean();

    public RabbitEventPublisher(RabbitBroker broker, ObjectMapper mapper, QueueProperties queue) {
        this.broker = broker;
        this.mapper = mapper;
        this.confirmTimeoutMs = queue.enqueueTimeoutMs();
    }

    @Override
    public PublishResult publish(SlackMessageEvent event, long receivedAtMs, String retryNum) {
        PublishResult result = send(event, 0, receivedAtMs, retryNum);
        if (result instanceof PublishResult.Enqueued) {
            // 즉시 반응 항목은 보조 기능이다 — 실패해도 이벤트는 이미 안전하게 저장됐으니 수락(200)을 막지 않는다.
            // 재시도·재처리 투입(republish)은 반응을 다시 만들지 않는다.
            // 알람처럼 원 메시지(ts)가 없는 이벤트는 반응을 붙일 곳이 없다.
            if (event.ts() != null) {
                publishReaction(event, receivedAtMs);
            }
        }
        return result;
    }

    private synchronized void publishReaction(SlackMessageEvent event, long receivedAtMs) {
        long start = System.nanoTime();
        try {
            var node = mapper.createObjectNode();
            node.put("event_id", event.eventId());
            node.put("channel", event.channel());
            node.put("ts", event.ts());
            node.put("received_at", receivedAtMs);
            Channel ch = confirmChannel();
            returned.set(false);
            ch.basicPublish("", broker.reactionsQueue(), true,
                    RabbitBroker.persistent(event.eventId(), Map.of()), mapper.writeValueAsBytes(node));
            // 수락(200) 경로에 더하는 대기다 — 이벤트 저장 예산(enqueue-timeout-ms)을 넘기지 않는다.
            if (!ch.waitForConfirms(confirmTimeoutMs) || returned.get()) {
                log.warn("반응 항목 저장 확인 실패(반응이 누락될 수 있음) event_id={}", event.eventId());
            }
            log.info("반응 항목 저장 event_id={} reaction_enqueue_ms={}", event.eventId(),
                    (System.nanoTime() - start) / 1_000_000);
        } catch (InterruptedException e) {
            discardChannel();
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            discardChannel();
            log.warn("반응 항목 발행 실패(반응이 누락될 수 있음) event_id={} reason={}", event.eventId(),
                    e.getClass().getSimpleName());
        }
    }

    @Override
    public PublishResult republish(SlackMessageEvent event, long gen, long receivedAtMs) {
        return send(event, gen, receivedAtMs, null);
    }

    private synchronized PublishResult send(SlackMessageEvent event, long gen, long receivedAtMs, String retryNum) {
        long start = System.nanoTime();
        byte[] body = EventMessageJson.write(mapper, event, gen, receivedAtMs, retryNum);

        // 1단계: 발행. 여기서 실패하면 브로커에 닿지 못했으므로 저장되지 않았음이 확실하다(Failed).
        Channel ch;
        try {
            ch = confirmChannel();
            returned.set(false);
            ch.basicPublish(broker.exchange(), broker.routingKey(), true,
                    RabbitBroker.persistent(event.eventId(), Map.of("gen", gen)), body);
        } catch (IOException | TimeoutException | RuntimeException e) {
            discardChannel();
            log.warn("큐 저장 실패 event_id={} reason={} detail={}", event.eventId(), e.getClass().getSimpleName(),
                    e.getMessage());
            return new PublishResult.Failed(ErrorInfo.of(ErrorCode.QUEUE_PUBLISH_FAILED, e));
        }

        // 2단계: 확인 대기. 요청이 나간 뒤라서 실패해도 저장 여부는 모른다(Unconfirmed, 규칙 4).
        boolean acked;
        try {
            acked = ch.waitForConfirms(confirmTimeoutMs);
        } catch (TimeoutException e) {
            // 확인이 늦을 뿐 브로커가 받았을 수 있다. 이 채널에는 미확인 발행이 남아 있어 계속 쓰면 늦게 온 ack·nack·return이
            // 다음 발행의 결과에 섞인다 — 채널을 버리고 다음 발행이 새 채널로 시작하게 한다.
            discardChannel();
            log.warn("큐 저장 확인 시간 초과 event_id={} timeout_ms={}", event.eventId(), confirmTimeoutMs);
            return new PublishResult.Unconfirmed(ErrorInfo.of(ErrorCode.QUEUE_CONFIRM_TIMEOUT));
        } catch (RuntimeException e) {
            discardChannel();
            log.warn("큐 저장 확인 중 연결 종료 event_id={} reason={}", event.eventId(), e.getClass().getSimpleName());
            return new PublishResult.Unconfirmed(ErrorInfo.of(ErrorCode.QUEUE_CONFIRM_INTERRUPTED, e));
        } catch (InterruptedException e) {
            discardChannel();
            Thread.currentThread().interrupt();
            return new PublishResult.Unconfirmed(ErrorInfo.of(ErrorCode.QUEUE_CONFIRM_INTERRUPTED, "interrupted"));
        }
        if (returned.get()) {
            // 큐에 라우팅되지 않았다 — 저장된 것이 아니다.
            log.warn("큐 저장 실패(라우팅 안 됨) event_id={}", event.eventId());
            return new PublishResult.Failed(ErrorInfo.of(ErrorCode.QUEUE_UNROUTABLE));
        }
        if (!acked) {
            log.warn("큐 저장 거절(nack) event_id={}", event.eventId());
            return new PublishResult.Failed(ErrorInfo.of(ErrorCode.QUEUE_NACKED));
        }
        long ms = (System.nanoTime() - start) / 1_000_000;
        log.info("큐 저장 확인 event_id={} gen={} enqueue_ms={}", event.eventId(), gen, ms);
        return new PublishResult.Enqueued(event.eventId() + ":" + gen);
    }

    /** 채널을 닫고 비운다. 다음 발행이 새 채널을 만든다. */
    private void discardChannel() {
        Channel old = channel;
        channel = null;
        if (old != null) {
            try {
                old.abort();
            } catch (IOException | RuntimeException ignored) {
                // 이미 닫힌 채널이다
            }
        }
    }

    private Channel confirmChannel() throws IOException, TimeoutException {
        if (channel == null || !channel.isOpen()) {
            Channel ch = broker.newChannel();
            ch.confirmSelect();
            ch.addReturnListener(r -> returned.set(true));
            channel = ch;
        }
        return channel;
    }

    /**
     * 놓아준 메시지를 지연 큐에 넣는다. 확인이 오고 라우팅됐으면 true. {@link RabbitDelivery#defer()}가 쓴다.
     * mandatory로 보낸다 — 지연 큐가 없는데 기본 교환기가 조용히 버리고 확인만 보내면 원본을 ack해 입력이 사라진다.
     */
    synchronized boolean publishDeferred(byte[] body, String messageId, long gen) {
        try {
            Channel ch = confirmChannel();
            returned.set(false);
            ch.basicPublish("", broker.deferQueue(), true, RabbitBroker.persistent(messageId, Map.of("gen", gen)), body);
            boolean acked = ch.waitForConfirms(confirmTimeoutMs > 1_000 ? confirmTimeoutMs : 1_000);
            if (returned.get()) {
                log.warn("놓아주기 발행 실패(라우팅 안 됨) event_id={}", messageId);
                return false;
            }
            return acked;
        } catch (IOException | TimeoutException | RuntimeException e) {
            discardChannel();
            log.warn("놓아주기 발행 실패 event_id={} reason={}", messageId, e.getClass().getSimpleName());
            return false;
        } catch (InterruptedException e) {
            discardChannel();
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
