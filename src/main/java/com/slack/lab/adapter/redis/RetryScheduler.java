package com.slack.lab.adapter.redis;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.QueueProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 도래한(retry_at &lt;= now) 재시도를 주기적으로 스트림에 재투입한다(M13). {@code retry_scheduler.lua}가
 * ZRANGEBYSCORE → XADD 성공 후 ZREM 순서를 원자적으로 수행한다 — 반대 순서면 ZREM 뒤 XADD가 실패했을 때
 * 입력이 유실된다. 중복 XADD는 안전하다: {@code state.lua}의 claim() 결과표가 BUSY·STALE·DONE으로
 * 중복 실행을 막는다.
 */
@Component
@ConditionalOnProperty(name = "queue.backend", havingValue = "redis", matchIfMissing = true)
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class RetryScheduler {

    private static final Logger log = LoggerFactory.getLogger(RetryScheduler.class);
    private static final long INTERVAL_MS = 5_000;
    private static final int BATCH_LIMIT = 50;

    private static final RedisScript<Long> SCRIPT = script();

    private final StringRedisTemplate redis;
    private final QueueProperties queueProps;
    // 테스트 전용 부분 실패 주입(N번째 쓰기 뒤 오류). 운영 빈에서는 항상 0이다.
    private int failAfter;

    private ScheduledExecutorService scheduler;

    public RetryScheduler(StringRedisTemplate redis, QueueProperties queueProps) {
        this.redis = redis;
        this.queueProps = queueProps;
    }

    private static RedisScript<Long> script() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("state/retry_scheduler.lua"));
        script.setResultType(Long.class);
        return script;
    }

    @PostConstruct
    void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory());
        scheduler.scheduleWithFixedDelay(this::runOnce, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
        log.info("재시도 스케줄러 시작 interval_ms={}", INTERVAL_MS);
    }

    @PreDestroy
    void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    private static ThreadFactory daemonThreadFactory() {
        return r -> {
            Thread t = new Thread(r, "retry-scheduler");
            t.setDaemon(true);
            return t;
        };
    }

    void runOnce() {
        try {
            Long requeued = redis.execute(SCRIPT, keys(), String.valueOf(failAfter), String.valueOf(BATCH_LIMIT),
                    "slack:preserved:", "slack:evt:");
            if (requeued != null && requeued > 0) {
                log.info("재시도 재투입 count={}", requeued);
            }
        } catch (RuntimeException e) {
            log.error("재시도 스케줄러 주기 오류", e);
        }
    }

    private List<String> keys() {
        return List.of(RedisProcessingStateStore.RETRY_KEY, queueProps.streamKey());
    }

    /** 테스트 전용: N번째 쓰기 뒤 실패를 주입해 XADD 성공·ZREM 실패의 부분 실패 복구를 검증한다. */
    void injectFailureAfter(int writes) {
        this.failAfter = writes;
    }
}
