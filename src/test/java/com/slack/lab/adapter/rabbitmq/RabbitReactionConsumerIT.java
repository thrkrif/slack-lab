package com.slack.lab.adapter.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.GetResponse;
import com.slack.lab.config.QueueProperties;
import com.slack.lab.config.RabbitProperties;
import com.slack.lab.config.ReactionProperties;
import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.model.ReactionResult;
import com.slack.lab.core.model.ReplyMetadata;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.model.SlackSendResult;
import com.slack.lab.core.port.ChatNotifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** 즉시 반응(RabbitMQ): 최초 발행 때만 본문 없는 항목을 만들고, 실패해도 재시도하지 않으며, 처리 큐와 독립이다. */
@Testcontainers
class RabbitReactionConsumerIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final QueueProperties QUEUE = new QueueProperties("unused", "unused", 1_000, 100_000);

    RabbitProperties props;
    final List<AutoCloseable> closeables = new ArrayList<>();
    final List<String> reactions = new CopyOnWriteArrayList<>();
    ReactionResult next = new ReactionResult(true, "added");

    @BeforeEach
    void setUp() {
        props = new RabbitProperties(RABBIT.getHost(), RABBIT.getAmqpPort(), "guest", "guest", "/", "rx" + System.nanoTime(),
                600, 3);
    }

    @AfterEach
    void tearDown() {
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 종료 중 오류는 무시한다
            }
        }
    }

    <T extends AutoCloseable> T track(T c) {
        closeables.add(c);
        return c;
    }

    ChatNotifier notifier() {
        return new ChatNotifier() {
            @Override
            public SlackSendResult postMessage(String c, String t, String text, long ms, ReplyMetadata m) {
                throw new AssertionError("반응 소비자는 답글을 보내지 않는다");
            }

            @Override
            public ReactionResult addReaction(String channel, String ts, String emoji) {
                reactions.add(channel + "|" + ts + "|" + emoji);
                return next;
            }
        };
    }

    static SlackMessageEvent event(String id) {
        return new SlackMessageEvent(id, "C1", "U1", "비밀 질문 본문", "100.1", null, null, null, null);
    }

    static void await(BooleanSupplier cond, long timeoutMs, String what) throws InterruptedException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < end) {
            if (cond.getAsBoolean()) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("시간 초과: " + what);
    }

    int depth(RabbitBroker broker) throws Exception {
        try (Channel ch = broker.newChannel()) {
            return ch.queueDeclarePassive(broker.reactionsQueue()).getMessageCount();
        }
    }

    @Test
    void 최초_발행은_본문_없는_반응_항목을_만든다() throws Exception {
        RabbitBroker broker = track(new RabbitBroker(props, 1));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);

        assertThat(publisher.publish(event("Ev1"), 1_700_000_000_000L, "0")).isInstanceOf(PublishResult.Enqueued.class);

        try (Channel ch = broker.newChannel()) {
            GetResponse r = ch.basicGet(broker.reactionsQueue(), true);
            assertThat(r).isNotNull();
            var node = MAPPER.readTree(r.getBody());
            assertThat(node.fieldNames()).toIterable().containsExactlyInAnyOrder("event_id", "channel", "ts", "received_at");
            assertThat(new String(r.getBody())).doesNotContain("비밀 질문 본문");
            assertThat(node.path("received_at").asLong()).isEqualTo(1_700_000_000_000L);
        }
    }

    @Test
    void 재투입은_반응_항목을_다시_만들지_않는다() throws Exception {
        RabbitBroker broker = track(new RabbitBroker(props, 1));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);
        publisher.publish(event("Ev2"), System.currentTimeMillis(), "0");
        assertThat(depth(broker)).isEqualTo(1);

        publisher.republish(event("Ev2"), 1, System.currentTimeMillis());

        assertThat(depth(broker)).as("재시도·재처리는 반응을 다시 붙이지 않는다").isEqualTo(1);
    }

    @Test
    void 반응_소비자는_이모지를_붙이고_항목을_지운다() throws Exception {
        RabbitBroker broker = track(new RabbitBroker(props, 2));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);
        var consumer = track(new RabbitReactionConsumer(broker, MAPPER, notifier(),
                new ReactionProperties("s", "g", "eyes", 10_000, 0)));
        consumer.start();

        publisher.publish(event("Ev3"), System.currentTimeMillis(), "0");

        await(() -> reactions.size() == 1, 10_000, "반응 호출");
        assertThat(reactions).containsExactly("C1|100.1|eyes");
        await(() -> {
            try {
                return depth(broker) == 0;
            } catch (Exception e) {
                return false;
            }
        }, 5_000, "항목 삭제");
    }

    @Test
    void 반응이_실패해도_재시도하지_않고_항목을_지운다() throws Exception {
        next = new ReactionResult(false, "missing_scope");
        RabbitBroker broker = track(new RabbitBroker(props, 2));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);
        track(new RabbitReactionConsumer(broker, MAPPER, notifier(), new ReactionProperties("s", "g", "eyes", 10_000, 0)))
                .start();

        publisher.publish(event("Ev4"), System.currentTimeMillis(), "0");

        await(() -> reactions.size() == 1, 10_000, "반응 호출");
        Thread.sleep(1_000); // 재시도가 있었다면 그 사이 더 호출됐을 것이다
        assertThat(reactions).hasSize(1);
        assertThat(depth(broker)).isZero();
    }

    @Test
    void 반응_큐가_없어도_이벤트_수락은_막히지_않는다() throws Exception {
        RabbitBroker broker = track(new RabbitBroker(props, 1));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);
        assertThat(publisher.publish(event("Ev0"), System.currentTimeMillis(), "0"))
                .isInstanceOf(PublishResult.Enqueued.class);
        try (Channel ch = broker.newChannel()) {
            ch.queueDelete(broker.reactionsQueue()); // 반응 큐가 사라졌다(운영자 실수 등) — 미라우팅이 된다
        }

        PublishResult result = publisher.publish(event("Ev5"), System.currentTimeMillis(), "0");

        assertThat(result).as("반응은 보조 기능이라 수락(200)을 막지 않는다").isInstanceOf(PublishResult.Enqueued.class);
    }
}
