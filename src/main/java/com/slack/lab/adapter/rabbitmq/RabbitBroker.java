package com.slack.lab.adapter.rabbitmq;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.slack.lab.config.RabbitProperties;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/**
 * RabbitMQ 연결과 토폴로지(ADR-9, M21). 연결은 자동 복구가 켜져 있다 — 복구되면 채널과 소비자는 다시 만들어지고, 복구 전에
 * 받은 메시지의 delivery tag는 새 채널에서 쓸 수 없다(그 메시지는 브로커가 다시 전달한다).
 *
 * <p>토폴로지
 * <pre>
 *   slack.events (direct exchange) → slack.events (quorum queue, delivery-limit, 초과분은 dead 큐로)
 *   slack.events.defer (classic queue, 고정 TTL, 만료되면 slack.events로 돌아간다) ← 지금 처리 못 하는 메시지를 놓아주는 곳
 *   slack.events.dead (quorum queue) ← 소비자가 계속 실패한 메시지
 * </pre>
 */
public class RabbitBroker implements AutoCloseable {

    private final RabbitProperties props;
    private final ConnectionFactory factory;
    private final ExecutorService consumerExecutor;
    private volatile Connection connection;

    public RabbitBroker(RabbitProperties props, int consumerThreads) {
        this.props = props;
        this.consumerExecutor = Executors.newFixedThreadPool(Math.max(1, consumerThreads), r -> {
            Thread t = new Thread(r, "rabbit-consumer");
            t.setDaemon(true);
            return t;
        });
        this.factory = new ConnectionFactory();
        factory.setHost(props.host());
        factory.setPort(props.port());
        factory.setUsername(props.username());
        factory.setPassword(props.password());
        factory.setVirtualHost(props.virtualHost());
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(2_000);
        factory.setRequestedHeartbeat(15);
        factory.setConnectionTimeout(5_000);
        // 소비자 콜백(LLM 포함 최대 50초)이 연결의 읽기 스레드를 막지 않게 전용 실행기에서 돌린다.
        factory.setSharedExecutor(consumerExecutor);
    }

    String eventsQueue() {
        return props.eventsQueue();
    }

    String exchange() {
        return props.eventsQueue();
    }

    String deferQueue() {
        return props.eventsQueue() + ".defer";
    }

    String deadQueue() {
        return props.eventsQueue() + ".dead";
    }

    String deadExchange() {
        return props.eventsQueue() + ".dlx";
    }

    /**
     * 연결을 돌려준다. <b>처음 한 번만 만든다</b> — 이후 끊기면 클라이언트의 자동 복구가 되살리므로 여기서 새 연결을 만들지
     * 않는다(복구 중인 연결을 버리면 누수가 생기고, 연결 시도가 락을 쥔 채 최대 5초 걸려 수신 p95를 깬다). 열려 있지 않으면
     * 즉시 실패한다.
     */
    Connection connection() throws IOException, TimeoutException {
        Connection current = connection;
        if (current != null) {
            if (!current.isOpen()) {
                throw new IOException("broker_unavailable");
            }
            return current;
        }
        return firstConnect();
    }

    private synchronized Connection firstConnect() throws IOException, TimeoutException {
        if (connection != null) {
            return connection;
        }
        Connection c = factory.newConnection("slack-lab");
        try (Channel ch = c.createChannel()) {
            declareTopology(ch);
        } catch (IOException | RuntimeException e) {
            c.abort(); // 토폴로지 선언에 실패한 연결은 남기지 않는다 — 다음 호출이 처음부터 다시 시도한다
            throw e;
        }
        connection = c;
        return c;
    }

    /** 연결이 열려 있는지. 헬스 체크용 — 끊긴 연결을 새로 만들지 않는다. 한 번도 연결한 적이 없으면 처음 연결을 시도한다. */
    boolean isUp() {
        try {
            return connection().isOpen();
        } catch (IOException | TimeoutException | RuntimeException e) {
            return false;
        }
    }

    Channel newChannel() throws IOException, TimeoutException {
        return connection().createChannel();
    }

    /** 발행 시 라우팅 키. 테스트가 미라우팅을 유도하려고 바꿔 끼운다. */
    String routingKey() {
        return eventsQueue();
    }

    /** 멱등 선언. 같은 인자로 이미 있으면 그대로 둔다(인자가 다르면 브로커가 거절한다). */
    void declareTopology(Channel ch) throws IOException {
        ch.exchangeDeclare(exchange(), "direct", true);
        ch.exchangeDeclare(deadExchange(), "direct", true);

        Map<String, Object> dead = new HashMap<>();
        dead.put("x-queue-type", "quorum");
        ch.queueDeclare(deadQueue(), true, false, false, dead);
        ch.queueBind(deadQueue(), deadExchange(), eventsQueue());

        Map<String, Object> events = new HashMap<>();
        events.put("x-queue-type", "quorum");
        events.put("x-delivery-limit", props.deliveryLimit());
        events.put("x-dead-letter-exchange", deadExchange());
        events.put("x-dead-letter-routing-key", eventsQueue());
        // 데드레터링도 유실 없이(at-least-once). quorum 큐에서 이 전략은 overflow=reject-publish가 필요하다.
        events.put("x-dead-letter-strategy", "at-least-once");
        events.put("x-overflow", "reject-publish");
        ch.queueDeclare(eventsQueue(), true, false, false, events);
        ch.queueBind(eventsQueue(), exchange(), eventsQueue());

        // 놓아준 메시지는 TTL 뒤 기본 교환기가 아니라 이벤트 교환기로 돌아간다.
        Map<String, Object> defer = new HashMap<>();
        defer.put("x-message-ttl", props.deferDelayMs());
        defer.put("x-dead-letter-exchange", exchange());
        defer.put("x-dead-letter-routing-key", eventsQueue());
        ch.queueDeclare(deferQueue(), true, false, false, defer);
    }

    /** 영속 메시지 속성. 브로커가 재시작해도 남는다. */
    static AMQP.BasicProperties persistent(String messageId, Map<String, Object> headers) {
        return new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).messageId(messageId)
                .headers(headers).build();
    }

    /** 테스트 전용: 프로세스가 죽은 것처럼 연결만 강제로 끊는다(진행 중인 소비자 스레드는 그대로 둔다). */
    synchronized void abortConnection() {
        if (connection != null) {
            connection.abort();
        }
    }

    @Override
    public synchronized void close() {
        try {
            if (connection != null && connection.isOpen()) {
                connection.close(2_000);
            }
        } catch (IOException | RuntimeException ignored) {
            // 종료 중 오류는 무시한다
        }
        consumerExecutor.shutdownNow();
    }
}
