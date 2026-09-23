package com.slack.lab.event;

import com.slack.lab.config.ExperimentProperties;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 인메모리 중복 억제 (P0). 재시작하면 잊는다 — 재시작·큐 재전달 복구는 P1이다.
 * 2단계에서 이 자리는 공유 저장소의 조건부 갱신으로 바뀐다.
 */
@Component
public class EventDeduplicator {

    private static final Logger log = LoggerFactory.getLogger(EventDeduplicator.class);

    /** COMPLETED·UNKNOWN 유지 기간 (ARCHITECTURE §3.2). FAILED도 같은 기간 뒤 축출해 맵이 무한히 커지지 않게 한다. */
    static final long RETENTION_NANOS = 10L * 60 * 1_000_000_000L;
    private static final long SWEEP_INTERVAL_NANOS = 60L * 1_000_000_000L;

    private static final Map<ProcessingState, Set<ProcessingState>> LEGAL = Map.of(
            ProcessingState.PROCESSING, Set.of(ProcessingState.SENDING, ProcessingState.FAILED),
            ProcessingState.SENDING, Set.of(ProcessingState.COMPLETED, ProcessingState.FAILED, ProcessingState.UNKNOWN));

    private record Entry(String attemptId, ProcessingState state, long startedNanos, long finishedNanos, String stage) {
        boolean expired(long now) {
            // 실행 중(PROCESSING·SENDING) 엔트리는 일반 TTL 청소에서 제외한다.
            return state.isTerminal() && now - finishedNanos >= RETENTION_NANOS;
        }
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private final boolean enabled;
    private final LongSupplier nanoClock;
    private volatile long lastSweepNanos;

    @Autowired
    public EventDeduplicator(ExperimentProperties experiment) {
        this(experiment.dedupEnabled(), System::nanoTime);
    }

    EventDeduplicator(boolean enabled, LongSupplier nanoClock) {
        this.enabled = enabled;
        this.nanoClock = nanoClock;
        this.lastSweepNanos = nanoClock.getAsLong();
    }

    /** 처리 권한을 원자적으로 선점한다. get 후 put은 동시 재전송에서 둘 다 통과하므로 쓰지 않는다. */
    public ClaimResult claim(String eventId) {
        long now = nanoClock.getAsLong();
        sweepIfDue(now);

        String attemptId = UUID.randomUUID().toString();
        // 실험 스위치: dedup을 끄면 매 호출이 독립 키를 받아 항상 선점된다(M8의 중복 답글 관측용).
        String key = enabled ? eventId : eventId + "#" + attemptId;

        AtomicBoolean claimed = new AtomicBoolean();
        Entry result = entries.compute(key, (k, cur) -> {
            if (cur == null || cur.expired(now) || cur.state() == ProcessingState.FAILED) {
                claimed.set(true);
                // FAILED 재선점: 새 attemptId가 이전 소유자의 지연 쓰기를 무효화한다.
                return new Entry(attemptId, ProcessingState.PROCESSING, now, 0, null);
            }
            return cur;
        });

        if (!claimed.get()) {
            return new ClaimResult.Duplicate(result.state());
        }
        return new ClaimResult.Claimed(new Handle(key, eventId, attemptId, now));
    }

    /** 소유자(attemptId)이고 현재 상태가 from일 때만 to로 바꾼다(CAS). */
    boolean transition(String key, String attemptId, ProcessingState from, ProcessingState to, String stage) {
        if (!LEGAL.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalArgumentException("허용되지 않는 전이 " + from + " → " + to);
        }
        long now = nanoClock.getAsLong();
        AtomicBoolean applied = new AtomicBoolean();
        entries.computeIfPresent(key, (k, cur) -> {
            if (!cur.attemptId().equals(attemptId) || cur.state() != from) {
                return cur;
            }
            applied.set(true);
            return new Entry(cur.attemptId(), to, cur.startedNanos(), to.isTerminal() ? now : 0, stage);
        });
        if (!applied.get()) {
            log.warn("상태 전이 거절 attempt_id={} {}→{} (소유자가 아니거나 상태가 다름)", attemptId, from, to);
        }
        return applied.get();
    }

    /** 테스트·로그용 조회. 선점 판단에는 쓰지 않는다. */
    ProcessingState stateOf(String eventId) {
        Entry e = entries.get(eventId);
        return e == null ? null : e.state();
    }

    int size() {
        return entries.size();
    }

    /** 만료된 종료 상태만 지운다. removeIf는 값 비교 삭제라 그 사이 재선점된 엔트리를 지우지 않는다. */
    void sweep() {
        long now = nanoClock.getAsLong();
        entries.entrySet().removeIf(e -> e.getValue().expired(now));
        lastSweepNanos = now;
    }

    private void sweepIfDue(long now) {
        if (now - lastSweepNanos >= SWEEP_INTERVAL_NANOS) {
            sweep();
        }
    }

    private final class Handle implements AttemptHandle {
        private final String key;
        private final String eventId;
        private final String attemptId;
        private final long startNanos;

        Handle(String key, String eventId, String attemptId, long startNanos) {
            this.key = key;
            this.eventId = eventId;
            this.attemptId = attemptId;
            this.startNanos = startNanos;
        }

        @Override public String eventId() { return eventId; }
        @Override public String attemptId() { return attemptId; }
        @Override public long startNanos() { return startNanos; }

        @Override
        public boolean markSending() {
            return transition(key, attemptId, ProcessingState.PROCESSING, ProcessingState.SENDING, null);
        }

        @Override
        public boolean markCompleted() {
            return transition(key, attemptId, ProcessingState.SENDING, ProcessingState.COMPLETED, null);
        }

        @Override
        public boolean markFailed(String stage) {
            return transition(key, attemptId, ProcessingState.PROCESSING, ProcessingState.FAILED, stage)
                    || transition(key, attemptId, ProcessingState.SENDING, ProcessingState.FAILED, stage);
        }

        @Override
        public boolean markUnknown(String stage) {
            return transition(key, attemptId, ProcessingState.SENDING, ProcessingState.UNKNOWN, stage);
        }
    }
}
