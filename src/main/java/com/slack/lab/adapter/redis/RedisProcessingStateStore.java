package com.slack.lab.adapter.redis;

import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;
import com.slack.lab.core.port.ProcessingStateStore;
import com.slack.lab.config.StateProperties;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.QueueProperties;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/** {@code state/state.lua} 한 스크립트가 선점 결과표와 모든 전이를 수행한다. 여기서는 인자만 맞춘다. */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.RECOVERY, AppRole.ALL})
public class RedisProcessingStateStore implements ProcessingStateStore {

    public static final String DLQ_KEY = "slack:dlq";
    public static final String RECOVERY_KEY = "slack:recovery";
    public static final String RETRY_KEY = "slack:retry";

    // 같은 스크립트를 반환 타입만 달리해 쓴다(선점은 목록, 나머지 전이는 0/1).
    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = script(List.class);
    private static final RedisScript<Long> SCRIPT_LONG = script(Long.class);

    private final StringRedisTemplate redis;
    private final StateProperties state;
    private final QueueProperties queue;
    // 테스트 전용 부분 실패 주입(N번째 쓰기 뒤 오류). 운영 빈에서는 항상 0이다.
    private int failAfter;

    public RedisProcessingStateStore(StringRedisTemplate redis, StateProperties state, QueueProperties queue) {
        this.redis = redis;
        this.state = state;
        this.queue = queue;
    }

    private static <T> RedisScript<T> script(Class<T> type) {
        DefaultRedisScript<T> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("state/state.lua"));
        script.setResultType(type);
        return script;
    }

    static String stateKey(String eventId) {
        return "slack:evt:" + eventId;
    }

    static String preservedKey(String eventId) {
        return "slack:preserved:" + eventId;
    }

    void injectFailureAfter(int writes) {
        this.failAfter = writes;
    }

    @Override
    public ClaimOutcome claim(ClaimRequest r) {
        String attemptId = UUID.randomUUID().toString();
        @SuppressWarnings("unchecked")
        List<Object> res = redis.execute(SCRIPT, keys(r.eventId()), "claim", r.eventId(), fail(),
                r.deliveryToken(), queue.group(), String.valueOf(r.gen()), attemptId,
                String.valueOf(state.leaseMs()), String.valueOf(Duration.ofHours(state.executionWindowHours()).toMillis()),
                String.valueOf(r.receivedAtMs()), nz(r.channel()), nz(r.threadTs()),
                String.valueOf(Duration.ofDays(state.completedRetentionDays()).toMillis()));
        String verdict = String.valueOf(res.get(0));
        return switch (verdict) {
            case "CLAIMED" -> new ClaimOutcome.Claimed(attemptId, Long.parseLong(String.valueOf(res.get(1))),
                    "1".equals(String.valueOf(res.get(2))), Integer.parseInt(String.valueOf(res.get(3))));
            case "BUSY" -> new ClaimOutcome.Busy();
            case "NO_INPUT" -> new ClaimOutcome.NoInput();
            default -> new ClaimOutcome.Settled(ClaimOutcome.Reason.valueOf(verdict));
        };
    }

    @Override
    public boolean markSending(String eventId, String attemptId) {
        return run(eventId, "mark_sending", attemptId);
    }

    @Override
    public boolean renew(String eventId, String attemptId) {
        return run(eventId, "renew", attemptId, String.valueOf(state.leaseMs()));
    }

    @Override
    public boolean finalizeAttempt(String eventId, String attemptId, String deliveryToken, Finalization f) {
        String dest = switch (f.preserveTo()) {
            case NONE -> "";
            case DLQ -> "dlq";
            case RECOVERY -> "recovery";
        };
        return run(eventId, "finalize", attemptId, f.state().name(), nz(deliveryToken), queue.group(), dest,
                nz(f.slackTs()), nz(f.kind()), nz(f.stage()),
                String.valueOf(Duration.ofDays(state.completedRetentionDays()).toMillis()));
    }

    @Override
    public boolean scheduleRetry(String eventId, String attemptId, String deliveryToken, long nextGen, long retryAtMs,
            int retries, String stage) {
        return run(eventId, "retry", attemptId, nz(deliveryToken), queue.group(), String.valueOf(nextGen),
                String.valueOf(retryAtMs), String.valueOf(retries), nz(stage));
    }

    private boolean run(String eventId, String op, String... args) {
        String[] all = new String[args.length + 3];
        all[0] = op;
        all[1] = eventId;
        all[2] = fail();
        System.arraycopy(args, 0, all, 3, args.length);
        Long res = redis.execute(SCRIPT_LONG, keys(eventId), (Object[]) all);
        return res != null && res == 1L;
    }

    private List<String> keys(String eventId) {
        return List.of(stateKey(eventId), queue.streamKey(), preservedKey(eventId), DLQ_KEY, RECOVERY_KEY, RETRY_KEY);
    }

    private String fail() {
        return String.valueOf(failAfter);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
