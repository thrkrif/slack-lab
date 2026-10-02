package com.slack.lab.core.service;

import com.slack.lab.core.port.BacklogProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 10초마다 적체 스냅샷을 로그 한 줄로 남긴다(M17). 지표 라이브러리는 도입하지 않는다(M7 결정 승계) — 로그를 grep해
 * 집계한다. 큐와 상태 저장소가 서로 다른 어댑터라 각 {@link BacklogProbe}의 값을 한 줄로 합친다. 워커가 여럿이면 각자
 * 남기지만 값은 전체 기준이라 어느 것을 봐도 같다.
 */
public class BacklogReporter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BacklogReporter.class);
    private static final long INTERVAL_MS = 10_000;

    private final List<BacklogProbe> probes;
    private volatile ScheduledExecutorService scheduler;

    public BacklogReporter(List<BacklogProbe> probes) {
        this.probes = probes;
    }

    public void start() {
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "backlog-reporter");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleWithFixedDelay(this::report, INTERVAL_MS, INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        ScheduledExecutorService s = scheduler;
        if (s != null) {
            s.shutdownNow();
        }
    }

    void report() {
        try {
            Map<String, Long> all = new LinkedHashMap<>();
            for (BacklogProbe probe : probes) {
                // 한 어댑터가 실패해도 다른 쪽 수치는 남긴다(큐가 죽어도 DB 적체는 보이게).
                try {
                    all.putAll(probe.snapshot());
                } catch (RuntimeException e) {
                    log.warn("적체 조회 일부 실패 probe={} reason={}", probe.getClass().getSimpleName(),
                            e.getClass().getSimpleName());
                }
            }
            StringBuilder line = new StringBuilder("적체 스냅샷");
            all.forEach((k, v) -> line.append(' ').append(k).append('=').append(v));
            log.info(line.toString());
        } catch (RuntimeException e) {
            log.warn("적체 스냅샷 실패 reason={}", e.getClass().getSimpleName());
        }
    }
}
