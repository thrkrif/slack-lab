package com.slack.lab.adapter.redis;

import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.model.RecoveryOutcome;
import com.slack.lab.config.StateProperties;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.QueueProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * recovery CLI가 쓰는 저장소(M14). 워커용 {@link RedisProcessingStateStore}와 같은 {@code state.lua}를 쓰되
 * 소유자 없이 사람이 부르는 전이({@code resolve}·{@code reprocess})만 노출한다. 자동 재발신 경로는 없다.
 */
@Component
@ConditionalOnRole(AppRole.RECOVERY)
public class RedisRecoveryStore implements RecoveryStore {

    @SuppressWarnings("rawtypes")
    private static final RedisScript<List> SCRIPT = script();

    private final StringRedisTemplate redis;
    private final QueueProperties queue;
    private final StateProperties state;
    // 테스트 전용 부분 실패 주입. 운영 빈에서는 항상 0이다.
    private int failAfter;

    public RedisRecoveryStore(StringRedisTemplate redis, QueueProperties queue, StateProperties state) {
        this.redis = redis;
        this.queue = queue;
        this.state = state;
    }

    @SuppressWarnings("rawtypes")
    private static RedisScript<List> script() {
        DefaultRedisScript<List> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("state/state.lua"));
        script.setResultType(List.class);
        return script;
    }

    void injectFailureAfter(int writes) {
        this.failAfter = writes;
    }

    /** DLQ와 복구 목록의 미해결 건을 보존 시각 순으로 돌려준다. */
    @Override
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        addAll(out, "recovery", RedisProcessingStateStore.RECOVERY_KEY);
        addAll(out, "dlq", RedisProcessingStateStore.DLQ_KEY);
        return out;
    }

    private void addAll(List<Entry> out, String name, String key) {
        Set<TypedTuple<String>> tuples = redis.opsForZSet().rangeWithScores(key, 0, -1);
        if (tuples == null) {
            return;
        }
        for (TypedTuple<String> t : tuples) {
            String id = t.getValue();
            Double score = t.getScore();
            Map<String, String> st = stateOf(id);
            Object reason = redis.opsForHash().get(RedisProcessingStateStore.preservedKey(id), "preserved_reason");
            out.add(new Entry(name, id, score == null ? 0 : score.longValue(), st,
                    reason == null ? "" : reason.toString()));
        }
    }

    @Override
    public Map<String, String> stateOf(String eventId) {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisProcessingStateStore.stateKey(eventId));
        Map<String, String> out = new LinkedHashMap<>();
        raw.forEach((k, v) -> out.put(k.toString(), v.toString()));
        return out;
    }

    /**
     * 보존 입력(없으면 상태 해시)에서 스레드 위치만 읽는다. 답글은 {@code thread_ts}가 있으면 그 스레드에, 없으면
     * 멘션 메시지({@code ts})를 루트로 달렸다(SlackMessageEvent.replyThreadTs). 상태 해시에는 {@code ts}가 없어
     * 보존본을 먼저 본다.
     */
    @Override
    public Optional<ThreadRef> threadRef(String eventId) {
        String pkey = RedisProcessingStateStore.preservedKey(eventId);
        String channel = field(pkey, "channel");
        String threadTs = field(pkey, "thread_ts");
        String ts = field(pkey, "ts");
        if (channel.isBlank()) {
            String skey = RedisProcessingStateStore.stateKey(eventId);
            channel = field(skey, "channel");
            if (threadTs.isBlank()) {
                threadTs = field(skey, "thread_ts");
            }
        }
        String root = threadTs.isBlank() ? ts : threadTs;
        if (channel.isBlank() || root.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new ThreadRef(channel, root));
    }

    private String field(String key, String name) {
        Object v = redis.opsForHash().get(key, name);
        return v == null ? "" : v.toString();
    }

    /** 스레드에서 답글이 실제로 있었음을 사람이 확인한 뒤 호출한다. 보존 해시·목록 항목을 함께 지운다(B18). */
    @Override
    public RecoveryOutcome resolveCompleted(String eventId, String slackTs) {
        return resolve(eventId, "COMPLETED", slackTs);
    }

    /** 더 처리하지 않기로 닫는다. */
    @Override
    public RecoveryOutcome close(String eventId) {
        return resolve(eventId, "CLOSED", "");
    }

    private RecoveryOutcome resolve(String eventId, String to, String slackTs) {
        return run(eventId, "resolve", to, slackTs, String.valueOf(Duration.ofDays(state.completedRetentionDays()).toMillis()));
    }

    /** 미전송이 확인된 건에 한 번의 수동 재실행을 승인한다. 승인은 그 gen의 첫 선점에서 소비된다. */
    @Override
    public RecoveryOutcome reprocess(String eventId) {
        return run(eventId, "reprocess");
    }

    private RecoveryOutcome run(String eventId, String op, String... args) {
        Object[] all = new Object[args.length + 3];
        all[0] = op;
        all[1] = eventId;
        all[2] = String.valueOf(failAfter);
        System.arraycopy(args, 0, all, 3, args.length);
        @SuppressWarnings("unchecked")
        List<Object> res = redis.execute(SCRIPT, keys(eventId), all);
        if (res == null || res.isEmpty()) {
            return new RecoveryOutcome("NO_RESULT", "");
        }
        return new RecoveryOutcome(String.valueOf(res.get(0)), res.size() > 1 ? String.valueOf(res.get(1)) : "");
    }

    private List<String> keys(String eventId) {
        return List.of(RedisProcessingStateStore.stateKey(eventId), queue.streamKey(),
                RedisProcessingStateStore.preservedKey(eventId), RedisProcessingStateStore.DLQ_KEY,
                RedisProcessingStateStore.RECOVERY_KEY, RedisProcessingStateStore.RETRY_KEY);
    }
}
