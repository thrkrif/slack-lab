package com.slack.lab.adapter.postgres;

import com.slack.lab.core.service.RetryRelay;
import com.slack.lab.core.service.UnknownResolver;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 워커 쪽 주기 작업(M22): 도래한 재시도를 큐에 다시 넣고, 결과 불명 건을 스레드 조회로 확인하고, 보존 기한이 지난 완료 건을
 * 지운다. 각 작업은 예외를 스스로 격리하므로(한 사이클의 실패가 주기를 멈추지 않는다) 여기서는 스케줄만 맡는다.
 */
public class PostgresMaintenance implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PostgresMaintenance.class);

    private final RetryRelay relay;
    private final UnknownResolver resolver; // null이면 자동 조회를 끈 것이다
    private final PostgresProcessingStateStore store;
    private final long resolverIntervalMs;
    private volatile ScheduledExecutorService scheduler;

    public PostgresMaintenance(RetryRelay relay, UnknownResolver resolver, PostgresProcessingStateStore store,
            long resolverIntervalMs) {
        this.relay = relay;
        this.resolver = resolver;
        this.store = store;
        this.resolverIntervalMs = resolverIntervalMs;
    }

    public void start() {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        scheduler = Executors.newScheduledThreadPool(2, r -> {
            Thread t = new Thread(r, "state-maintenance-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(relay::runOnce, 5_000, 5_000, TimeUnit.MILLISECONDS);
        scheduler.scheduleWithFixedDelay(this::purge, 60_000, 600_000, TimeUnit.MILLISECONDS);
        if (resolver != null) {
            scheduler.scheduleWithFixedDelay(resolver::runOnce, resolverIntervalMs, resolverIntervalMs,
                    TimeUnit.MILLISECONDS);
        }
        log.info("상태 유지보수 시작 retry_relay_ms=5000 unknown_auto_check={}", resolver != null);
    }

    private void purge() {
        try {
            int n = store.purgeExpired();
            if (n > 0) {
                log.info("보존 기한이 지난 완료 건 삭제 count={}", n);
            }
        } catch (RuntimeException e) {
            log.error("만료 건 삭제 오류", e);
        }
    }

    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            try {
                // 실행 중이던 사이클이 이미 닫힌 DataSource를 써서 종료 때마다 오류 로그를 내지 않게 잠깐 기다린다.
                scheduler.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
