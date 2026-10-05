package com.slack.lab.adapter.rabbitmq;

import com.slack.lab.TestFailures;
import com.slack.lab.core.model.MessageKind;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.port.QueueDelivery;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.slack.lab.adapter.postgres.PostgresMigrations;
import com.slack.lab.adapter.postgres.PostgresProcessingStateStore;
import com.slack.lab.config.ExperimentProperties;
import com.slack.lab.config.LlmProperties;
import com.slack.lab.config.ProcessingProperties;
import com.slack.lab.config.QueueProperties;
import com.slack.lab.config.RabbitProperties;
import com.slack.lab.config.RetryProperties;
import com.slack.lab.config.SlackProperties;
import com.slack.lab.config.StateProperties;
import com.slack.lab.config.WorkerProperties;
import com.slack.lab.core.model.LlmResult;
import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.model.ReactionResult;
import com.slack.lab.core.model.ReplyMetadata;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.model.SlackSendResult;
import com.slack.lab.core.port.ChatNotifier;
import com.slack.lab.core.port.LlmClient;
import com.slack.lab.core.port.ThreadContextSource;
import com.slack.lab.core.service.EventProcessor;
import com.slack.lab.core.service.RetryPolicy;
import com.slack.lab.core.service.RetryRelay;
import com.slack.lab.core.service.SlackEventHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * RabbitMQ 큐 어댑터 + Postgres 상태 저장소 + 코어를 직접 이어 붙인 통합 테스트(M21). 실제 브로커·DB 컨테이너로
 * 발행 확인 → 소비 → 선점 → 발신 → 확정 → ack, 소비자 강제 종료 뒤 재전달, 중복 전달, 지연 재시도 릴레이, 독약 메시지를
 * 검증한다. LLM·Slack은 가짜다(발신 횟수를 센다).
 */
@Testcontainers
class RabbitPipelineIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-alpine");

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>(com.slack.lab.TestImages.POSTGRES);

    static final ObjectMapper MAPPER = new ObjectMapper();
    static final StateProperties STATE = new StateProperties(1_500, 300, 7, 24);
    static final QueueProperties QUEUE = new QueueProperties(1_000);

    static HikariDataSource ds;
    static JdbcTemplate jdbc;

    RabbitProperties rabbitProps;
    final List<AutoCloseable> closeables = new ArrayList<>();
    final List<String> posts = new CopyOnWriteArrayList<>();
    PostgresProcessingStateStore store;

    @BeforeAll
    static void connect() {
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(PG.getJdbcUrl());
        c.setUsername(PG.getUsername());
        c.setPassword(PG.getPassword());
        c.setMaximumPoolSize(12);
        ds = new HikariDataSource(c);
        PostgresMigrations.migrate(ds);
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void close() {
        ds.close();
    }

    @BeforeEach
    void reset() throws Exception {
        jdbc.update("TRUNCATE processing_state, preserved_input");
        posts.clear();
        // 테스트마다 다른 큐 이름을 써서 이전 테스트가 남긴 메시지와 섞이지 않게 한다.
        String queue = "ev" + System.nanoTime();
        rabbitProps = new RabbitProperties(RABBIT.getHost(), RABBIT.getAmqpPort(), "guest", "guest", "/", queue, 600, 3);
        store = new PostgresProcessingStateStore(ds, STATE, MAPPER);
    }

    @AfterEach
    void cleanup() {
        for (AutoCloseable c : closeables) {
            try {
                c.close();
            } catch (Exception ignored) {
                // 종료 중 오류는 무시한다
            }
        }
        closeables.clear();
    }

    // --- 도우미

    <T extends AutoCloseable> T track(T c) {
        closeables.add(c);
        return c;
    }

    ChatNotifier notifier() {
        return new ChatNotifier() {
            @Override
            public SlackSendResult postMessage(String channel, String threadTs, String text,
                    com.slack.lab.core.model.ReplyFooter footer, long remainingMs, ReplyMetadata metadata) {
                posts.add(metadata.eventId() + "|" + text);
                return new SlackSendResult.Success("ts-" + posts.size());
            }

            @Override
            public ReactionResult addReaction(String channel, String ts, String emoji) {
                return new ReactionResult(true, "added");
            }
        };
    }

    EventProcessor processor(LlmClient llm, StateProperties state) {
        SlackEventHandler handler = new SlackEventHandler(llm, notifier(),
                new LlmProperties(LlmProperties.Client.OLLAMA, "http://x/v1", "m", 512, "30m", 50_000, 500, true),
                new SlackProperties("s", "t", "https://slack.com/api", 10_000), new ProcessingProperties(60_000),
                new ExperimentProperties(0, false), ThreadContextSource.NONE);
        RetryPolicy retry = new RetryPolicy(new RetryProperties(List.of(300L, 300L, 300L), 3), store);
        return new EventProcessor(store, handler, retry, state, new WorkerProperties(1));
    }

    record Worker(RabbitBroker broker, RabbitEventPublisher publisher, RabbitConsumer consumer) {}

    Worker startWorker(EventProcessor processor) throws Exception {
        RabbitBroker broker = track(new RabbitBroker(rabbitProps, 2));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);
        RabbitConsumer consumer = track(new RabbitConsumer(broker, publisher, processor, MAPPER, 1));
        consumer.start();
        return new Worker(broker, publisher, consumer);
    }

    RabbitEventPublisher receiverPublisher() {
        RabbitBroker broker = track(new RabbitBroker(rabbitProps, 1));
        return new RabbitEventPublisher(broker, MAPPER, QUEUE);
    }

    static SlackMessageEvent event(String id) {
        return new SlackMessageEvent(id, "C1", "U1", "<@UB> 안녕", "100.1", null, null, null, null);
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

    String stateOf(String id) {
        List<String> l = jdbc.queryForList("SELECT state FROM processing_state WHERE event_id = ?", String.class, id);
        return l.isEmpty() ? null : l.get(0);
    }

    int ready(String queue) throws Exception {
        try (Channel ch = track(new RabbitBroker(rabbitProps, 1)).newChannel()) {
            return ch.queueDeclarePassive(queue).getMessageCount();
        }
    }

    static LlmClient answers() {
        return (messages, ms) -> new LlmResult.Success("답변", 1);
    }

    // --- 정상 왕복

    @Test
    void 발행이_확인되고_소비_선점_발신_확정_ack까지_끝난다() throws Exception {
        startWorker(processor(answers(), STATE));
        RabbitEventPublisher publisher = receiverPublisher();

        PublishResult result = publisher.publish(event("Ev1"), System.currentTimeMillis(), "0");

        assertThat(result).isInstanceOf(PublishResult.Enqueued.class);
        await(() -> "COMPLETED".equals(stateOf("Ev1")), 10_000, "COMPLETED");
        assertThat(posts).containsExactly("Ev1|답변");
        await(() -> {
            try {
                return ready(rabbitProps.eventsQueue()) == 0;
            } catch (Exception e) {
                return false;
            }
        }, 5_000, "큐 비움");
    }

    @Test
    void 동일_event_id를_10번_발행해도_답글은_한_번이다() throws Exception {
        startWorker(processor(answers(), STATE));
        RabbitEventPublisher publisher = receiverPublisher();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 10; i++) {
            assertThat(publisher.publish(event("Dup"), now, "0")).isInstanceOf(PublishResult.Enqueued.class);
        }

        await(() -> "COMPLETED".equals(stateOf("Dup")), 10_000, "COMPLETED");
        Thread.sleep(2_500); // 놓아준 중복이 지연 큐를 돌아 다시 와도 추가 발신이 없어야 한다

        assertThat(posts).hasSize(1);
        assertThat(ready(rabbitProps.eventsQueue()) + ready(rabbitProps.eventsQueue() + ".defer")).isZero();
        assertThat(ready(rabbitProps.eventsQueue() + ".dead")).as("정상 중복 처리는 데드레터로 새지 않는다").isZero();
    }

    // --- 소비자 강제 종료 (B4)

    @Test
    void 처리_중인_소비자가_죽으면_다른_소비자가_임대_만료_뒤_이어받아_답글은_한_번이다() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LlmClient blocking = (messages, ms) -> {
            entered.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new LlmResult.Success("죽은 워커의 답", 1);
        };
        // 첫 워커는 임대를 갱신하지 못하는 상태(죽은 프로세스)를 흉내 낸다: 갱신 주기를 테스트 시간보다 길게 둔다.
        Worker first = startWorker(processor(blocking, new StateProperties(1_500, 600_000, 7, 24)));
        RabbitEventPublisher publisher = receiverPublisher();
        publisher.publish(event("Kill"), System.currentTimeMillis(), "0");
        assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(stateOf("Kill")).isEqualTo("PROCESSING");

        startWorker(processor(answers(), STATE));
        first.broker().abortConnection(); // 프로세스가 죽은 것처럼 연결만 끊긴다 → 브로커가 unacked 메시지를 되돌린다

        await(() -> "COMPLETED".equals(stateOf("Kill")), 20_000, "다른 워커가 이어받아 완료");
        release.countDown();
        Thread.sleep(500); // 죽었던 워커의 핸들러가 깨어나도 소유권이 없어 발신하지 못한다

        assertThat(posts).containsExactly("Kill|답변");
        assertThat(ready(rabbitProps.eventsQueue() + ".dead")).as("임대 만료를 기다리는 동안 데드레터로 새지 않는다").isZero();
    }

    // --- 지연 재시도 릴레이

    @Test
    void 재시도_요청은_상태에_예약되고_릴레이가_다음_세대로_다시_넣어_한_번_발신한다() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        LlmClient timeoutThenOk = (messages, ms) -> calls.incrementAndGet() == 1 ? new LlmResult.TimedOut(1)
                : new LlmResult.Success("재시도 답", 1);
        Worker worker = startWorker(processor(timeoutThenOk, STATE));
        RabbitEventPublisher publisher = receiverPublisher();
        ScheduledExecutorService relayLoop = Executors.newSingleThreadScheduledExecutor();
        closeables.add(relayLoop::shutdownNow);
        RetryRelay relay = new RetryRelay(store, worker.publisher(), 10, 5_000);
        relayLoop.scheduleWithFixedDelay(relay::runOnce, 100, 200, TimeUnit.MILLISECONDS);

        publisher.publish(event("Retry"), System.currentTimeMillis(), "0");

        await(() -> "COMPLETED".equals(stateOf("Retry")), 15_000, "재시도 후 완료");
        assertThat(posts).containsExactly("Retry|재시도 답");
        assertThat(calls.get()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT gen FROM processing_state WHERE event_id = 'Retry'", Long.class))
                .isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM preserved_input WHERE event_id = 'Retry'", Integer.class))
                .as("완료되면 재시도 보존본이 정리된다").isZero();
    }

    @Test
    void 릴레이는_큐가_확인하기_전까지_반영_시각을_남기지_않아_다음_사이클이_다시_시도한다() throws Exception {
        // 소비자 없이 재시도 예약 상태만 만든다
        var claim = new com.slack.lab.core.model.ClaimRequest("Relay", "t", 0, System.currentTimeMillis(), "C1", "100.1",
                event("Relay"));
        var claimed = (com.slack.lab.core.model.ClaimOutcome.Claimed) store.claim(claim);
        assertThat(store.scheduleRetry("Relay", claimed.attemptId(), "t", 1, System.currentTimeMillis() - 60_000, 1, TestFailures.send("x")))
                .isTrue();
        // (도래 시각을 충분히 과거로 둔 이유: 컨테이너 DB 시계와 JVM 시계가 몇 ms 어긋날 수 있다)
        // 브로커에 닿지 않는 발행기(잘못된 포트)로는 재투입이 실패한다
        RabbitProperties bad = new RabbitProperties("127.0.0.1", 1, "guest", "guest", "/", "x", 600, 3);
        RabbitEventPublisher broken = new RabbitEventPublisher(track(new RabbitBroker(bad, 1)), MAPPER, QUEUE);

        assertThat(new RetryRelay(store, broken, 10, 60_000).runOnce()).isZero();
        assertThat(jdbc.queryForObject("SELECT relayed_at FROM preserved_input WHERE event_id = 'Relay'", Long.class))
                .as("확인되지 않았으니 반영 시각이 없다").isNull();

        RabbitEventPublisher good = receiverPublisher();
        assertThat(new RetryRelay(store, good, 10, 60_000).runOnce()).isEqualTo(1);
        assertThat(new RetryRelay(store, good, 10, 60_000).runOnce()).as("반영 직후에는 건너뛴다").isZero();
    }

    // --- 발행 실패

    @Test
    void 브로커에_닿지_못하면_Failed를_돌려주고_수신_서버는_200을_주지_않는다() {
        RabbitProperties bad = new RabbitProperties("127.0.0.1", 1, "guest", "guest", "/", "x", 600, 3);
        RabbitEventPublisher broken = new RabbitEventPublisher(track(new RabbitBroker(bad, 1)), MAPPER, QUEUE);

        PublishResult result = broken.publish(event("Down"), System.currentTimeMillis(), "0");

        assertThat(result).isInstanceOf(PublishResult.Failed.class);
    }

    // --- 독약 메시지와 전달 횟수 상한

    @Test
    void 읽을_수_없는_메시지는_데드레터_큐로_가고_소비자는_계속_동작한다() throws Exception {
        startWorker(processor(answers(), STATE));
        try (Channel ch = track(new RabbitBroker(rabbitProps, 1)).newChannel()) {
            ch.basicPublish(rabbitProps.eventsQueue(), rabbitProps.eventsQueue(), null, "깨진{본문".getBytes());
        }

        await(() -> {
            try {
                return ready(rabbitProps.eventsQueue() + ".dead") == 1;
            } catch (Exception e) {
                return false;
            }
        }, 10_000, "데드레터 큐에 도착");
        RabbitEventPublisher publisher = receiverPublisher();
        publisher.publish(event("After"), System.currentTimeMillis(), "0");
        await(() -> "COMPLETED".equals(stateOf("After")), 10_000, "이후 메시지 정상 처리");
    }

    @Test
    void 소비자가_계속_예외를_던지면_전달_횟수_상한_뒤_데드레터_큐로_간다() throws Exception {
        EventProcessor throwing = new EventProcessor(store, null, null, STATE, new WorkerProperties(1)) {
            @Override
            public void process(com.slack.lab.core.port.QueueDelivery delivery) {
                throw new IllegalStateException("처리 불가");
            }
        };
        startWorker(throwing);
        receiverPublisher().publish(event("Poison"), System.currentTimeMillis(), "0");

        await(() -> {
            try {
                return ready(rabbitProps.eventsQueue() + ".dead") == 1;
            } catch (Exception e) {
                return false;
            }
        }, 15_000, "전달 횟수 상한 뒤 데드레터");
        assertThat(posts).isEmpty();
    }

    // --- 발행 확인 지연 측정 (EXPERIMENT-LOG에 기록): 수신 p95 200ms 예산 안에서 쓰는 값이다

    @Test
    void 발행_확인_지연을_측정한다() throws Exception {
        RabbitEventPublisher publisher = receiverPublisher();
        long now = System.currentTimeMillis();
        for (int i = 0; i < 20; i++) { // 워밍업(연결·토폴로지 선언·JIT)
            publisher.publish(event("W" + i), now, "0");
        }
        long[] seq = new long[100];
        for (int i = 0; i < seq.length; i++) {
            long t0 = System.nanoTime();
            PublishResult r = publisher.publish(event("S" + i), now, "0");
            seq[i] = (System.nanoTime() - t0) / 1_000_000;
            assertThat(r).isInstanceOf(PublishResult.Enqueued.class);
        }
        // 동시 5건(버스트): 발행이 한 채널에서 직렬화되므로 마지막 호출은 앞선 호출을 기다린다
        int threads = 5;
        int perThread = 20;
        long[] burst = new long[threads * perThread];
        var pool = Executors.newFixedThreadPool(threads);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int t = 0; t < threads; t++) {
            int tid = t;
            futures.add(pool.submit(() -> {
                start.await();
                for (int i = 0; i < perThread; i++) {
                    long t0 = System.nanoTime();
                    publisher.publish(event("B" + tid + "_" + i), now, "0");
                    burst[tid * perThread + i] = (System.nanoTime() - t0) / 1_000_000;
                }
                return null;
            }));
        }
        start.countDown();
        for (var f : futures) {
            f.get();
        }
        pool.shutdownNow();
        java.util.Arrays.sort(seq);
        java.util.Arrays.sort(burst);
        System.out.printf("RABBIT_PUBLISH_CONFIRM_MS seq n=%d p50=%d p95=%d max=%d | burst5 n=%d p50=%d p95=%d max=%d%n",
                seq.length, seq[seq.length / 2 - 1], seq[(int) Math.ceil(0.95 * seq.length) - 1], seq[seq.length - 1],
                burst.length, burst[burst.length / 2 - 1], burst[(int) Math.ceil(0.95 * burst.length) - 1],
                burst[burst.length - 1]);
        // 수신 p95 200ms 예산(큐 확인 대기 포함)을 넘으면 알린다
        assertThat(burst[(int) Math.ceil(0.95 * burst.length) - 1]).isLessThan(200);
    }

    // --- 확인(confirm) 실패 경로: 규칙 4(결과 불명은 성공도 명확한 실패도 아니다)와 규칙 5(오류를 유도해 확인)

    /** 라우팅 키를 바꿔 끼운 브로커. 어느 큐에도 묶이지 않은 키로 발행하게 한다. */
    RabbitBroker brokerWithRoutingKey(String key) {
        return track(new RabbitBroker(rabbitProps, 1) {
            @Override
            String routingKey() {
                return key;
            }
        });
    }

    @Test
    void 큐에_라우팅되지_않으면_Failed이고_저장된_것으로_보지_않는다() {
        RabbitEventPublisher publisher = new RabbitEventPublisher(brokerWithRoutingKey("묶이지-않은-키"), MAPPER, QUEUE);

        PublishResult result = publisher.publish(event("Unroutable"), System.currentTimeMillis(), "0");

        assertThat(result).isEqualTo(new PublishResult.Failed(ErrorInfo.of(ErrorCode.QUEUE_UNROUTABLE)));
    }

    @Test
    void 브로커가_거절하면_nack으로_Failed를_돌려준다() throws Exception {
        RabbitBroker broker = brokerWithRoutingKey("rp-key");
        // 용량 1에 reject-publish인 큐를 묶는다: 두 번째 발행은 브로커가 nack한다
        try (Channel ch = broker.newChannel()) {
            ch.queueDeclare("rp-queue", true, false, false,
                    java.util.Map.of("x-max-length", 1, "x-overflow", "reject-publish"));
            ch.queueBind("rp-queue", broker.exchange(), "rp-key");
        }
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);

        assertThat(publisher.publish(event("Full1"), System.currentTimeMillis(), "0"))
                .isInstanceOf(PublishResult.Enqueued.class);
        assertThat(publisher.publish(event("Full2"), System.currentTimeMillis(), "0"))
                .isEqualTo(new PublishResult.Failed(ErrorInfo.of(ErrorCode.QUEUE_NACKED)));
    }

    @Test
    void 확인이_늦으면_Unconfirmed이고_채널을_버려_다음_발행은_영향받지_않는다() throws Exception {
        QueueProperties shortConfirm = new QueueProperties(400);
        RabbitBroker broker = track(new RabbitBroker(rabbitProps, 1));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, shortConfirm);
        assertThat(publisher.publish(event("Warm"), System.currentTimeMillis(), "0"))
                .isInstanceOf(PublishResult.Enqueued.class);

        var docker = RABBIT.getDockerClient();
        docker.pauseContainerCmd(RABBIT.getContainerId()).exec(); // 브로커가 응답하지 못하는 상태
        PublishResult during;
        try {
            during = publisher.publish(event("Paused"), System.currentTimeMillis(), "0");
        } finally {
            docker.unpauseContainerCmd(RABBIT.getContainerId()).exec();
        }

        assertThat(during).as("요청은 나갔지만 저장 여부를 모른다").isInstanceOf(PublishResult.Unconfirmed.class);
        // 늦게 도착한 ack가 있더라도 새 채널로 시작하므로 다음 발행의 결과는 자기 것이다
        // 일시 정지가 길면 브로커가 연결을 끊고 자동 복구가 되살리는 동안은 실패할 수 있다(느린 장비의 전체 빌드에서 관측) —
        // 복구 뒤에는 성공해야 한다.
        PublishResult after = null;
        for (int i = 0; i < 20; i++) {
            after = publisher.publish(event("After"), System.currentTimeMillis(), "0");
            if (after instanceof PublishResult.Enqueued) {
                break;
            }
            // 늦은 ack가 버려진 채널을 오염시켰다면 Unconfirmed가 나온다 — 그것은 재시도로 덮지 않는다.
            assertThat(after).isNotInstanceOf(PublishResult.Unconfirmed.class);
            Thread.sleep(500);
        }
        assertThat(after).isInstanceOf(PublishResult.Enqueued.class);
    }

    @Test
    void 연결이_닫혀_있으면_새_연결을_만들지_않고_즉시_실패하며_헬스는_DOWN이다() throws Exception {
        RabbitBroker broker = track(new RabbitBroker(rabbitProps, 1));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);
        assertThat(publisher.publish(event("Up"), System.currentTimeMillis(), "0"))
                .isInstanceOf(PublishResult.Enqueued.class);

        broker.abortConnection(); // 앱이 낸 종료라 자동 복구 대상이 아니다 — 닫힌 연결을 만났을 때의 동작을 본다
        long t0 = System.nanoTime();
        PublishResult during = publisher.publish(event("Cut"), System.currentTimeMillis(), "0");
        boolean healthy = broker.isUp();
        long ms = (System.nanoTime() - t0) / 1_000_000;

        assertThat(during).isInstanceOf(PublishResult.Failed.class);
        assertThat(healthy).isFalse();
        assertThat(ms).as("복구 중인 연결을 기다리거나 새로 만들지 않는다").isLessThan(1_000);
    }

    @Test
    void 브로커가_연결을_끊으면_자동_복구로_다시_발행할_수_있다() throws Exception {
        RabbitBroker broker = track(new RabbitBroker(rabbitProps, 1));
        RabbitEventPublisher publisher = new RabbitEventPublisher(broker, MAPPER, QUEUE);
        assertThat(publisher.publish(event("R0"), System.currentTimeMillis(), "0"))
                .isInstanceOf(PublishResult.Enqueued.class);

        RABBIT.execInContainer("rabbitmqctl", "close_all_connections", "브로커 측 강제 종료");

        await(() -> publisher.publish(event("R1"), System.currentTimeMillis(), "0") instanceof PublishResult.Enqueued,
                20_000, "자동 복구 뒤 발행");
        assertThat(broker.isUp()).isTrue();
    }
}
