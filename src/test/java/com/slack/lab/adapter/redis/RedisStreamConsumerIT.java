package com.slack.lab.adapter.redis;

import com.slack.lab.core.service.EventProcessor;
import com.slack.lab.core.service.RetryPolicy;
import com.slack.lab.config.RetryProperties;
import com.slack.lab.config.WorkerProperties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.lab.core.port.AttemptHandle;
import com.slack.lab.core.model.HandlingResult;
import com.slack.lab.core.service.SlackEventHandler;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.config.QueueProperties;
import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;
import com.slack.lab.core.port.ProcessingStateStore;
import com.slack.lab.config.StateProperties;
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
class RedisStreamConsumerIT {

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

    RedisStreamConsumer worker;

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

    private static final RetryProperties RETRY_PROPS = new RetryProperties(java.util.List.of(50L, 50L, 50L), 3);

    private RedisStreamConsumer newWorker(ProcessingStateStore store, SlackEventHandler handler) {
        return newWorker(STATE, store, handler);
    }

    private RedisStreamConsumer newWorker(StateProperties stateProps, ProcessingStateStore store, SlackEventHandler handler) {
        RetryPolicy retryPolicy = new RetryPolicy(RETRY_PROPS, store);
        EventProcessor processor = new EventProcessor(store, handler, retryPolicy, stateProps, WORKER_PROPS);
        RedisStreamConsumer w = new RedisStreamConsumer(redis, QUEUE, WORKER_PROPS, processor);
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
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean())).thenAnswer(inv -> {
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
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean())).thenAnswer(inv -> {
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
        verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean());
    }

    // --- 3. Failed → DEAD + DLQ + ACK

    @Test
    void Failed_결과는_DEAD와_DLQ에_기록되고_ACK된다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean()))
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
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean()))
                .thenReturn(new HandlingResult.Rejected("mark_sending_rejected"));

        newWorker(store, handler);
        RecordId id = publishRaw("W4");

        awaitTrue(() -> {
            try {
                verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean());
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });

        sleep(300); // 재확인 창 — 즉시 재처리되지 않아야 한다
        verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean());
        assertThat(stateOf("W4")).containsEntry("state", "PROCESSING");
        assertThat(pending()).isEqualTo(1);
        assertThat(inStream(id)).isTrue();
    }

    // --- 5. 핸들러 예외: markSending 이전 → Failed, 이후 → Unknown

    @Test
    void markSending_이전_예외는_Failed로_귀결된다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        SlackEventHandler throwsBeforeMark = new SlackEventHandler(null, null, null, null, null, null, null) {
            @Override
            public HandlingResult handle(SlackMessageEvent event, AttemptHandle attempt, boolean finalAttempt) {
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
        SlackEventHandler throwsAfterMark = new SlackEventHandler(null, null, null, null, null, null, null) {
            @Override
            public HandlingResult handle(SlackMessageEvent event, AttemptHandle attempt, boolean finalAttempt) {
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
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean())).thenAnswer(inv -> {
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

            @Override
            public boolean scheduleRetry(String eventId, String attemptId, String streamId, long nextGen,
                    long retryAtMs, int retries, String stage) {
                return delegate.scheduleRetry(eventId, attemptId, streamId, nextGen, retryAtMs, retries, stage);
            }
        };
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean()))
                .thenReturn(new HandlingResult.Delivered("answer", "999.9"));

        newWorker(finalizeThrows, handler);
        RecordId id = publishRaw("W7");

        awaitTrue(() -> {
            try {
                verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean());
                return true;
            } catch (AssertionError e) {
                return false;
            }
        });

        sleep(300); // 워커 루프의 오류 로그·재시도 대기가 끝날 시간을 준다
        assertThat(pending()).isEqualTo(1);
        assertThat(inStream(id)).isTrue();
        verify(handler, times(1)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean());
    }

    // --- M13: RetryRequested → RETRY_WAIT(안내 없이 ACK), finalAttempt 전달, 재시도 뒤 완료/소진

    @Test
    void RetryRequested_결과는_안내_없이_RETRY_WAIT으로_ACK되고_세대와_재시도_횟수가_오른다() {
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        RetryPolicy retryPolicy = new RetryPolicy(RETRY_PROPS, store);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean()))
                .thenReturn(new HandlingResult.RetryRequested("llm_timeout(elapsed_ms=0)", 0));

        RedisStreamConsumer w = new RedisStreamConsumer(redis, QUEUE, WORKER_PROPS,
                new EventProcessor(store, handler, retryPolicy, STATE, WORKER_PROPS));
        w.start();
        this.worker = w;
        publishRaw("W8");

        awaitTrue(() -> "RETRY_WAIT".equals(stateOf("W8").get("state")));
        awaitTrue(() -> pending() == 0); // 안내 없이 ACK됐다
        assertThat(stateOf("W8")).containsEntry("gen", "1").containsEntry("retries", "1");
        assertThat(listed(DLQ_KEY, "W8")).isFalse();
        assertThat(listed(RECOVERY_KEY, "W8")).isFalse();
    }

    @Test
    void finalAttempt는_retries가_max_retries에_도달했을_때_true로_전달되고_마지막_시도는_종료로_이어진다() {
        // maxRetries=1인 정책으로, 최초 시도(retries=0)에는 false를, 재시도 뒤(retries=1)에는 true를
        // 핸들러에 넘겨야 한다 — 재시도를 계속할지는 핸들러(SlackEventHandler)가 이 값으로 판단한다(M13).
        //
        // codex critic REVISE MINOR-4: 이전에는 마지막 시도(finalAttempt=true)에서도 mock이 계속
        // RetryRequested를 돌려주게 해뒀다 — 실제 SlackEventHandler는 finalAttempt일 때 RetryRequested를
        // 절대 돌려주지 않는다(계약 위반, SlackEventHandler.send()·chat()이 `&& !finalAttempt`로 막는다).
        // 이 위반을 mock으로 재현하면 RetryPolicy.scheduleRetry()가 backoffMs.get(currentRetries)를
        // currentRetries=1(리스트 크기 1)로 호출해 범위를 벗어난다 — 아래 별도 테스트에서 재현해 확인한다.
        // **정정**: 이제 RetryPolicy에 방어적 clamp가 있어 그 호출은 더 이상 IndexOutOfBoundsException을
        // 던지지 않는다(클램프된 백오프 값으로 계속 진행). 계약을 지키는 정상 동작(마지막 시도는 실제
        // 종료 결과를 반환)도 함께 검증한다.
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        RetryProperties oneRetry = new RetryProperties(java.util.List.of(50L), 1);
        RetryPolicy retryPolicy = new RetryPolicy(oneRetry, store);
        RetryScheduler scheduler = new RetryScheduler(redis, QUEUE);

        SlackEventHandler handler = mock(SlackEventHandler.class);
        org.mockito.ArgumentCaptor<Boolean> finalAttemptCaptor = org.mockito.ArgumentCaptor.forClass(Boolean.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), finalAttemptCaptor.capture()))
                .thenReturn(new HandlingResult.RetryRequested("llm_timeout", 0))
                .thenAnswer(inv -> {
                    // 계약대로: 마지막 시도는 재시도를 요청하지 않고 실제 종료 결과를 돌려준다.
                    AttemptHandle attempt = inv.getArgument(1);
                    attempt.markSending();
                    return new HandlingResult.Delivered("answer", "500.1");
                });

        RedisStreamConsumer w = new RedisStreamConsumer(redis, QUEUE, WORKER_PROPS,
                new EventProcessor(store, handler, retryPolicy, STATE, WORKER_PROPS));
        w.start();
        this.worker = w;
        publishRaw("W9");

        awaitTrue(() -> "RETRY_WAIT".equals(stateOf("W9").get("state")) && "1".equals(stateOf("W9").get("retries")));
        sleep(120); // 예약된 재시도(50ms)가 도래할 시간을 준다
        scheduler.runOnce(); // 재투입 — 워커가 두 번째(마지막) 시도를 집는다

        awaitTrue(() -> finalAttemptCaptor.getAllValues().size() >= 2);
        assertThat(finalAttemptCaptor.getAllValues().get(0)).isFalse(); // retries=0
        assertThat(finalAttemptCaptor.getAllValues().get(1)).isTrue(); // retries=1 == maxRetries

        // 계약을 지키는 마지막 시도는 정상 종료(COMPLETED)로 이어지고 ACK된다 — 재시도로 되돌아가지 않는다.
        awaitTrue(() -> "COMPLETED".equals(stateOf("W9").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(stateOf("W9")).containsEntry("slack_ts", "500.1");
        verify(handler, times(2)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean());
    }

    @Test
    void 마지막_시도에서_계약을_어기고_재시도를_요청해도_방어적_clamp로_크래시하지_않는다() {
        // codex critic REVISE MINOR-4가 요구한 재현이었던 IndexOutOfBoundsException 시나리오: finalAttempt=true인데도
        // 핸들러가 RetryRequested를 돌려주면(계약 위반) backoffMs.get(currentRetries) 호출이 currentRetries(1)
        // >= backoffMs 크기(1)라 범위를 벗어났었다. 이건 SlackEventHandler가 스스로는 절대 만들지 않는
        // 입력이라 프로덕션 버그는 아니었지만, **정정**: "실제 핸들러 경로로는 도달 불가"라던 이전 서술은
        // 부정확했다 — StartupInvariants(backoffMs.size() >= maxRetries)는 Spring 기동 경로에서만 검사되고,
        // 이 테스트처럼 빈을 직접 생성하는 경로(단위 테스트, 또는 향후 다른 조립 경로)는 그 검사를 거치지
        // 않는다. code-reviewer가 retry.max-retries=4·기본 backoffMs 3개 조합으로 실제 크래시를 재현해 확인했다.
        // 그래서 RetryPolicy.scheduleRetry()에 방어적 clamp(Math.min(currentRetries, backoffMs.size()-1))를
        // 추가했다 — 불변식이 있어도 이중 방어가 낫다. 이제 이 계약 위반 입력도 마지막 백오프 값으로 클램프돼
        // 예외 없이 넘어간다(아래에서 확인).
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        RetryProperties oneRetry = new RetryProperties(java.util.List.of(50L), 1);
        RetryPolicy retryPolicy = new RetryPolicy(oneRetry, store);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> retryPolicy.scheduleRetry("W9x", "attempt", "0-1", 1, 1, 0, "llm_timeout")))
                .as("clamp 덕에 currentRetries(1) >= backoffMs 크기(1)여도 더는 IndexOutOfBoundsException을 던지지 않는다")
                .isNull();
    }

    @Test
    void StartupInvariants를_우회하는_설정_조합에서도_clamp가_크래시를_막는다() {
        // code-reviewer가 직접 재현: retry.max-retries=4인데 backoffMs가 기본값(3개) 그대로면 StartupInvariants가
        // 기동 시점에는 막지만(retry.backoff-ms 항목 수 >= retry.max-retries), 그 검사를 거치지 않고 조립된
        // RetryProperties(예: 이 테스트처럼 직접 생성)에서는 currentRetries=3(4번째 재시도)에서
        // backoffMs.get(3)이 크기 3짜리 리스트의 범위를 벗어나 실제로 크래시했다. clamp는 이런 설정 실수에도
        // 마지막 백오프 값(120000ms)으로 계속 진행하게 한다.
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        RetryProperties misconfigured = new RetryProperties(java.util.List.of(5000L, 30000L, 120000L), 4);
        RetryPolicy retryPolicy = new RetryPolicy(misconfigured, store);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> retryPolicy.scheduleRetry("W9y", "attempt", "0-1", 1, 3, 0, "llm_timeout")))
                .as("backoffMs.size()=3인데 currentRetries=3(index out of range)이어도 clamp가 마지막 값을 써 크래시하지 않는다")
                .isNull();
    }

    @Test
    void 재시도_가능한_오류는_예약_뒤_스케줄러가_재투입하면_다시_실행돼_결국_완료된다() {
        // 전이표 "LLM 재시도 가능, 마지막 시도 전 → 안내 없이 RETRY_WAIT" 이후 재투입되면 정상 완료되는
        // 전체 왕복을 검증한다.
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        RetryPolicy retryPolicy = new RetryPolicy(RETRY_PROPS, store);
        RetryScheduler scheduler = new RetryScheduler(redis, QUEUE);

        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean()))
                .thenReturn(new HandlingResult.RetryRequested("llm_timeout", 0))
                .thenAnswer(inv -> {
                    AttemptHandle attempt = inv.getArgument(1);
                    attempt.markSending();
                    return new HandlingResult.Delivered("answer", "500.1");
                });

        RedisStreamConsumer w = new RedisStreamConsumer(redis, QUEUE, WORKER_PROPS,
                new EventProcessor(store, handler, retryPolicy, STATE, WORKER_PROPS));
        w.start();
        this.worker = w;
        publishRaw("W10");

        awaitTrue(() -> "RETRY_WAIT".equals(stateOf("W10").get("state")));
        sleep(120); // backoff(50ms)가 도래할 시간을 준다
        scheduler.runOnce();

        awaitTrue(() -> "COMPLETED".equals(stateOf("W10").get("state")));
        awaitTrue(() -> pending() == 0);
        assertThat(stateOf("W10")).containsEntry("slack_ts", "500.1").containsEntry("gen", "1");
        verify(handler, times(2)).handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean());
    }

    @Test
    void 최종_안내가_성공하면_COMPLETED로_끝나고_kind는_failure_notice다() {
        // 전이표 "최종 안내 성공 → COMPLETED(kind=failure_notice), 정상 답변 지표에서 제외"
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        RetryPolicy retryPolicy = new RetryPolicy(RETRY_PROPS, store);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean())).thenAnswer(inv -> {
            AttemptHandle attempt = inv.getArgument(1);
            attempt.markSending();
            return new HandlingResult.Delivered("failure_notice", "600.1");
        });

        RedisStreamConsumer w = new RedisStreamConsumer(redis, QUEUE, WORKER_PROPS,
                new EventProcessor(store, handler, retryPolicy, STATE, WORKER_PROPS));
        w.start();
        this.worker = w;
        publishRaw("W11");

        awaitTrue(() -> "COMPLETED".equals(stateOf("W11").get("state")));
        assertThat(stateOf("W11")).containsEntry("kind", "failure_notice").containsEntry("slack_ts", "600.1");
    }

    @Test
    void 최종_안내_발신이_실패하면_DEAD와_DLQ로_끝난다() {
        // 전이표 "최종 안내 명확한 실패 → DEAD+DLQ"
        ProcessingStateStore store = new RedisProcessingStateStore(redis, STATE, QUEUE);
        RetryPolicy retryPolicy = new RetryPolicy(RETRY_PROPS, store);
        SlackEventHandler handler = mock(SlackEventHandler.class);
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean()))
                .thenReturn(new HandlingResult.Failed("failure_notice_send:channel_not_found", false));

        RedisStreamConsumer w = new RedisStreamConsumer(redis, QUEUE, WORKER_PROPS,
                new EventProcessor(store, handler, retryPolicy, STATE, WORKER_PROPS));
        w.start();
        this.worker = w;
        publishRaw("W12");

        awaitTrue(() -> "DEAD".equals(stateOf("W12").get("state")));
        assertThat(listed(DLQ_KEY, "W12")).isTrue();
    }
}
