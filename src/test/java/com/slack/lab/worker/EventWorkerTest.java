package com.slack.lab.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.lab.event.AttemptHandle;
import com.slack.lab.event.HandlingResult;
import com.slack.lab.event.SlackEventHandler;
import com.slack.lab.event.SlackMessageEvent;
import com.slack.lab.queue.QueueProperties;
import com.slack.lab.state.ClaimOutcome;
import com.slack.lab.state.ClaimRequest;
import com.slack.lab.state.Finalization;
import com.slack.lab.state.ProcessingStateStore;
import com.slack.lab.state.RedisProcessingStateStore;
import com.slack.lab.state.StateProperties;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 워커의 결과 분류(PLAN 2단계 M12 "M12 시점의 결과 처리")를 실제 Redis로 검증한다. {@code SlackEventHandler}는
 * 목·익명 서브클래스로 대체해 결과(예외 포함)를 자유롭게 만들고, 큐 메시지는 {@link EventPublisher}를 거치지
 * 않고 {@code EventPublisher.fields()}와 같은 스키마로 직접 `XADD`한다.
 */
@Testcontainers
class EventWorkerTest {

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.2-alpine").withExposedPorts(6379);

    static final long LEASE_MS = 400;
    static final QueueProperties QUEUE = new QueueProperties("slack:events", "workers", 150, 100000);
    static final StateProperties STATE = new StateProperties(LEASE_MS, 100, 7, 24);
    static final WorkerProperties WORKER_PROPS = new WorkerProperties(1);
    static final String DLQ_KEY = "slack:dlq";
    static final String RECOVERY_KEY = "slack:recovery";

    static LettuceConnectionFactory factory;
    static StringRedisTemplate redis;

    EventWorker worker;

    @BeforeAll
    static void connect() {
        factory = new LettuceConnectionFactory(
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379)));
        factory.afterPropertiesSet();
        redis = new StringRedisTemplate(factory);
        redis.afterPropertiesSet();
    }

    @AfterAll
    static void close() {
        factory.destroy();
    }

    @BeforeEach
    void reset() {
        redis.execute(connection -> {
            connection.serverCommands().flushAll();
            return null;
        }, true);
    }

    @AfterEach
    void tearDown() {
        if (worker != null) {
            worker.stop();
        }
    }

    // --- 도우미

    private static String stateKey(String eventId) {
        return "slack:evt:" + eventId;
    }

    private RecordId publishRaw(String eventId, String channel, String ts, String threadTs, String user,
            String text) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("event_id", eventId);
        fields.put("gen", "0");
        fields.put("received_at", String.valueOf(System.currentTimeMillis()));
        fields.put("channel", channel);
        fields.put("ts", ts);
        fields.put("thread_ts", threadTs == null ? "" : threadTs);
        fields.put("user", user == null ? "" : user);
        fields.put("text", text == null ? "" : text);
        return redis.opsForStream().add(MapRecord.create(QUEUE.streamKey(), fields));
    }

    private RecordId publishRaw(String eventId) {
        return publishRaw(eventId, "C1", "100.1", null, "U1", "질문");
    }

    private Map<Object, Object> stateOf(String eventId) {
        return redis.opsForHash().entries(stateKey(eventId));
    }

    private long pending() {
        return redis.opsForStream().pending(QUEUE.streamKey(), QUEUE.group()).getTotalPendingMessages();
    }

    private boolean inStream(RecordId id) {
        return !redis.opsForStream().range(QUEUE.streamKey(), Range.just(id.getValue())).isEmpty();
    }

    private boolean listed(String listKey, String eventId) {
        return redis.opsForZSet().score(listKey, eventId) != null;
    }

    private EventWorker newWorker(ProcessingStateStore store, SlackEventHandler handler) {
        return newWorker(STATE, store, handler);
    }

    private EventWorker newWorker(StateProperties stateProps, ProcessingStateStore store, SlackEventHandler handler) {
        EventWorker w = new EventWorker(redis, QUEUE, WORKER_PROPS, stateProps, store, handler);
        w.start();
        this.worker = w;
        return w;
    }

    private static void awaitTrue(BooleanSupplier condition) {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadlineNanos) {
            if (condition.getAsBoolean()) {
                return;
            }
            sleep(50);
        }
        fail("조건이 시간 안에 충족되지 않음");
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // --- 1. Delivered → COMPLETED + ACK

    @Test
    void Delivered_결과는_COMPLETED로_기록되고_ACK된다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class))).thenAnswer(inv -> {
            // COMPLETED는 SENDING에서만 전이한다(state.lua) — 실제 핸들러처럼 발신 전 게이트를 통과시킨다.
            AttemptHandle attempt = inv.getArgument(1);
            attempt.markSending();
            return new HandlingResult.Delivered("answer", "200.1");
        });

        newWorker(store, handler);
        publishRaw("W1");

        awaitTrue(() -> "COMPLETED".equals(stateOf("W1").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(stateOf("W1")).containsEntry("slack_ts", "200.1");
    }

    // --- 2. Unknown → UNKNOWN + 복구 목록 + ACK, 재시도 0회

    @Test
    void Unknown_결과는_UNKNOWN과_복구_목록에_기록되고_ACK되며_재시도하지_않는다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class))).thenAnswer(inv -> {
            // UNKNOWN도 SENDING에서만 전이한다(state.lua) — 발신 시도 자체는 한 것으로 취급한다.
            AttemptHandle attempt = inv.getArgument(1);
            attempt.markSending();
            return new HandlingResult.Unknown("answer_send:timeout");
        });

        newWorker(store, handler);
        publishRaw("W2");

        awaitTrue(() -> "UNKNOWN".equals(stateOf("W2").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(listed(RECOVERY_KEY, "W2")).isTrue();

        sleep(500); // 재전달·재시도가 뒤늦게 일어나지 않는지 여유를 두고 확인
        verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class));
    }

    // --- 3. Failed → DEAD + DLQ + ACK

    @Test
    void Failed_결과는_DEAD와_DLQ에_기록되고_ACK된다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class)))
                .thenReturn(new HandlingResult.Failed("answer_send:channel_not_found", false));

        newWorker(store, handler);
        publishRaw("W3");

        awaitTrue(() -> "DEAD".equals(stateOf("W3").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(listed(DLQ_KEY, "W3")).isTrue();
    }

    // --- 4. Rejected → ACK 안 함, 상태 그대로, 재호출 없음

    @Test
    void Rejected_결과는_ACK하지_않고_상태를_건드리지_않으며_재호출하지_않는다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class)))
                .thenReturn(new HandlingResult.Rejected("mark_sending_rejected"));

        newWorker(store, handler);
        RecordId id = publishRaw("W4");

        awaitTrue(() -> {
            try {
                verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class));
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });

        sleep(300); // 재확인 창 — 즉시 재처리되지 않아야 한다
        verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class));
        assertThat(stateOf("W4")).containsEntry("state", "PROCESSING");
        assertThat(pending()).isEqualTo(1);
        assertThat(inStream(id)).isTrue();
    }

    // --- 5. 핸들러 예외: markSending 이전 → Failed, 이후 → Unknown

    @Test
    void markSending_이전_예외는_Failed로_귀결된다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler throwsBeforeMark = new SlackEventHandler(null, null, null, null, null, null) {
            @Override
            public HandlingResult handle(SlackMessageEvent event, AttemptHandle attempt) {
                throw new RuntimeException("markSending 전 예외");
            }
        };

        newWorker(store, throwsBeforeMark);
        publishRaw("W5");

        awaitTrue(() -> "DEAD".equals(stateOf("W5").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(listed(DLQ_KEY, "W5")).isTrue();
    }

    @Test
    void markSending_이후_예외는_Unknown으로_귀결된다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler throwsAfterMark = new SlackEventHandler(null, null, null, null, null, null) {
            @Override
            public HandlingResult handle(SlackMessageEvent event, AttemptHandle attempt) {
                attempt.markSending();
                throw new RuntimeException("markSending 후 예외");
            }
        };

        newWorker(store, throwsAfterMark);
        publishRaw("W6");

        awaitTrue(() -> "UNKNOWN".equals(stateOf("W6").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(listed(RECOVERY_KEY, "W6")).isTrue();
    }

    // --- 5b. MAJOR-1 회귀: 임대보다 오래 걸려도 주기적 갱신이 소유권을 지켜 markSending이 성공한다

    @Test
    void 처리가_임대보다_오래_걸려도_주기적_갱신으로_markSending이_성공한다() {
        // lease=400ms인데 핸들러가 markSending() 전에 600ms를 기다린다 — 갱신(renewMs=100ms)이 없으면
        // 임대가 이미 만료돼 markSending()이 거절(Rejected)돼야 정상이다. 갱신이 있으면 Delivered로 끝난다.
        StateProperties shortLease = new StateProperties(LEASE_MS, 100, 7, 24);
        ProcessingStateStore store = new RedisProcessingStateStore(redis, shortLease, QUEUE);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class))).thenAnswer(inv -> {
            sleep(600); // 임대(400ms)보다 길게 대기 — 갱신 없이는 여기서 이미 소유권을 잃는다
            AttemptHandle attempt = inv.getArgument(1);
            boolean marked = attempt.markSending();
            if (!marked) {
                return new HandlingResult.Rejected("mark_sending_rejected");
            }
            return new HandlingResult.Delivered("answer", "300.1");
        });

        newWorker(shortLease, store, handler);
        publishRaw("W5B");

        awaitTrue(() -> "COMPLETED".equals(stateOf("W5B").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(stateOf("W5B")).containsEntry("slack_ts", "300.1");
    }

    // --- 6. finalize 자체가 실패(연결 끊김 등) → ACK 안 함, 입력 유실 없음

    @Test
    void finalize가_예외를_던지면_ACK하지_않고_메시지가_pending에_남는다() {
        ProcessingStateStore delegate = new RedisProcessingStateStore(redis, STATE, QUEUE);
        ProcessingStateStore finalizeThrows = new ProcessingStateStore() {
            @Override
            public ClaimOutcome claim(ClaimRequest request) {
                return delegate.claim(request);
            }

            @Override
            public boolean markSending(String eventId, String attemptId) {
                return delegate.markSending(eventId, attemptId);
            }

            @Override
            public boolean renew(String eventId, String attemptId) {
                return delegate.renew(eventId, attemptId);
            }

            @Override
            public boolean finalizeAttempt(String eventId, String attemptId, String streamId, Finalization f) {
                throw new RuntimeException("finalize 중 연결 끊김");
            }
        };
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class)))
                .thenReturn(new HandlingResult.Delivered("answer", "999.9"));

        newWorker(finalizeThrows, handler);
        RecordId id = publishRaw("W7");

        awaitTrue(() -> {
            try {
                verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class));
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });

        sleep(300); // 워커 루프의 오류 로그·재시도 대기가 끝날 시간을 준다
        assertThat(pending()).isEqualTo(1);
        assertThat(inStream(id)).isTrue();
        verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class));
    }
}
