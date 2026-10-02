package com.slack.lab.adapter.redis;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.QueueProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 10초마다 큐 적체 스냅샷을 로그에 남긴다(M17). 지표 라이브러리는 도입하지 않는다(M7 결정 승계) — 로그를 grep해
 * 집계한다. 워커가 여럿이면 각자 남기지만 값은 모두 Redis 전체 기준이라 어느 것을 봐도 같다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class BacklogReporter {

    private static final Logger log = LoggerFactory.getLogger(BacklogReporter.class);
    private static final long INTERVAL_MS = 10_000;

    private final StringRedisTemplate redis;
    private final QueueProperties queue;
    private ScheduledExecutorService scheduler;

    public BacklogReporter(StringRedisTemplate redis, QueueProperties queue) {
        this.redis = redis;
        this.queue = queue;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backlog-reporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::report, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    void report() {
        try {
            Long len = redis.opsForStream().size(queue.streamKey());
            long pending = redis.opsForStream().pending(queue.streamKey(), queue.group()).getTotalPendingMessages();
            log.info("적체 스냅샷 stream_len={} pending={} retry={} dlq={} recovery={}", nz(len), pending,
                    nz(redis.opsForZSet().zCard(RedisProcessingStateStore.RETRY_KEY)),
                    nz(redis.opsForZSet().zCard(RedisProcessingStateStore.DLQ_KEY)),
                    nz(redis.opsForZSet().zCard(RedisProcessingStateStore.RECOVERY_KEY)));
        } catch (RuntimeException e) {
            log.warn("적체 스냅샷 실패 reason={}", e.getClass().getSimpleName());
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
