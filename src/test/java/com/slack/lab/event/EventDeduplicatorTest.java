package com.slack.lab.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class EventDeduplicatorTest {

    final AtomicLong now = new AtomicLong(1_000);
    final EventDeduplicator dedup = new EventDeduplicator(true, now::get);

    AttemptHandle claim(String id) {
        return ((ClaimResult.Claimed) dedup.claim(id)).handle();
    }

    @Test
    void 동시_N회_선점하면_정확히_1승이다() throws Exception {
        int n = 64;
        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch ready = new CountDownLatch(n);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<ClaimResult>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return dedup.claim("Ev1");
            }));
        }
        ready.await();
        go.countDown();
        long wins = 0;
        for (var f : fs) {
            if (f.get() instanceof ClaimResult.Claimed) wins++;
        }
        pool.shutdown();
        assertThat(wins).isEqualTo(1);
    }

    @Test
    void 처리_중이면_중복이고_기존_상태를_알려준다() {
        claim("Ev1");
        assertThat(dedup.claim("Ev1")).isEqualTo(new ClaimResult.Duplicate(ProcessingState.PROCESSING));
    }

    @Test
    void 정상_흐름은_PROCESSING_SENDING_COMPLETED이고_완료_뒤에도_중복이다() {
        var h = claim("Ev1");
        assertThat(h.markSending()).isTrue();
        assertThat(dedup.stateOf("Ev1")).isEqualTo(ProcessingState.SENDING);
        assertThat(h.markCompleted()).isTrue();
        assertThat(dedup.claim("Ev1")).isEqualTo(new ClaimResult.Duplicate(ProcessingState.COMPLETED));
    }

    @Test
    void 결과_불명은_UNKNOWN이고_재선점되지_않는다() {
        var h = claim("Ev1");
        h.markSending();
        assertThat(h.markUnknown("send")).isTrue();
        assertThat(dedup.claim("Ev1")).isEqualTo(new ClaimResult.Duplicate(ProcessingState.UNKNOWN));
    }

    @Test
    void 소유자가_아닌_핸들의_전이는_거절된다() {
        var old = claim("Ev1");
        old.markFailed("llm");
        var fresh = claim("Ev1"); // FAILED 재선점
        assertThat(old.markSending()).isFalse();
        assertThat(old.markFailed("late")).isFalse();
        assertThat(dedup.stateOf("Ev1")).isEqualTo(ProcessingState.PROCESSING);
        assertThat(fresh.markSending()).isTrue();
    }

    @Test
    void FAILED는_재선점되고_attemptId가_바뀐다() {
        var first = claim("Ev1");
        first.markFailed("llm");
        var second = claim("Ev1");
        assertThat(second.attemptId()).isNotEqualTo(first.attemptId());
    }

    @Test
    void 종료된_핸들은_다시_전이할_수_없다() {
        var h = claim("Ev1");
        h.markSending();
        h.markCompleted();
        assertThat(h.markUnknown("x")).isFalse();
        assertThat(h.markFailed("x")).isFalse();
        assertThat(dedup.stateOf("Ev1")).isEqualTo(ProcessingState.COMPLETED);
    }

    @Test
    void SENDING을_건너뛴_완료는_불가하다() {
        var h = claim("Ev1");
        assertThat(h.markCompleted()).isFalse();
        assertThat(h.markUnknown("x")).isFalse();
    }

    @Test
    void 허용되지_않는_전이_요청은_예외다() {
        claim("Ev1");
        assertThatThrownBy(() -> dedup.transition("Ev1", "a", ProcessingState.COMPLETED, ProcessingState.PROCESSING, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void TTL_경계_10분_직전까지는_중복이고_10분에_재선점된다() {
        var h = claim("Ev1");
        h.markSending();
        h.markCompleted();
        long done = now.get();
        now.set(done + EventDeduplicator.RETENTION_NANOS - 1);
        assertThat(dedup.claim("Ev1")).isInstanceOf(ClaimResult.Duplicate.class);
        now.set(done + EventDeduplicator.RETENTION_NANOS);
        assertThat(dedup.claim("Ev1")).isInstanceOf(ClaimResult.Claimed.class);
    }

    @Test
    void 청소는_실행_중_엔트리를_지우지_않고_만료된_종료_엔트리만_지운다() {
        claim("processing");
        var sending = claim("sending");
        sending.markSending();
        var done = claim("done");
        done.markSending();
        done.markCompleted();
        var failed = claim("failed");
        failed.markFailed("llm");

        now.addAndGet(EventDeduplicator.RETENTION_NANOS * 10);
        dedup.sweep();

        assertThat(dedup.stateOf("processing")).isEqualTo(ProcessingState.PROCESSING);
        assertThat(dedup.stateOf("sending")).isEqualTo(ProcessingState.SENDING);
        assertThat(dedup.stateOf("done")).isNull();
        assertThat(dedup.stateOf("failed")).isNull();
    }

    @Test
    void 선점_호출이_주기적으로_청소해_맵이_무한히_커지지_않는다() {
        for (int i = 0; i < 100; i++) {
            var h = claim("e" + i);
            h.markFailed("llm");
        }
        assertThat(dedup.size()).isEqualTo(100);
        now.addAndGet(EventDeduplicator.RETENTION_NANOS + 1);
        dedup.claim("trigger");
        assertThat(dedup.size()).isEqualTo(1);
    }

    @Test
    void dedup을_끄면_같은_이벤트도_매번_선점된다() {
        var off = new EventDeduplicator(false, now::get);
        var a = off.claim("Ev1");
        var b = off.claim("Ev1");
        assertThat(a).isInstanceOf(ClaimResult.Claimed.class);
        assertThat(b).isInstanceOf(ClaimResult.Claimed.class);
        // 서로의 상태를 건드리지 않는다.
        var ha = ((ClaimResult.Claimed) a).handle();
        var hb = ((ClaimResult.Claimed) b).handle();
        assertThat(ha.markSending()).isTrue();
        assertThat(hb.markSending()).isTrue();
    }
}
