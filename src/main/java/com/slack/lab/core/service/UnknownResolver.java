package com.slack.lab.core.service;

import com.slack.lab.core.model.ScanResult;
import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.port.RecoveryStore.Entry;
import com.slack.lab.core.port.RecoveryStore.ThreadRef;
import com.slack.lab.core.port.ThreadLookup;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 결과 불명(UNKNOWN) 건을 스레드 조회로 확인한다(ADR-9 6항, M22). 사람이 CLI로 하던 {@code check}의 읽기 전용 부분만
 * 자동화한다: 우리 메타데이터가 달린 답글이 있으면 완료 처리하고, 없거나 조회할 수 없으면 아무것도 바꾸지 않는다.
 * <b>자동 재발신은 하지 않는다</b>(규칙 11). 미발견 건은 목록에 남아 사람이 재처리 여부를 정한다.
 */
public class UnknownResolver {

    private static final Logger log = LoggerFactory.getLogger(UnknownResolver.class);
    private static final int MAX_PER_CYCLE = 10;

    private final RecoveryStore store;
    private final ThreadLookup threads;
    private final long minAgeMs;

    public UnknownResolver(RecoveryStore store, ThreadLookup threads, long minAgeMs) {
        this.store = store;
        this.threads = threads;
        this.minAgeMs = minAgeMs;
    }

    /** @return 이번 사이클에 완료 처리한 건수 */
    public int runOnce() {
        try {
            return resolve();
        } catch (RuntimeException e) {
            // 한 사이클의 예외가 주기 실행을 영구히 멈추지 않게 한다.
            log.error("결과 불명 자동 조회 사이클 오류 — 다음 사이클에 다시 시도", e);
            return 0;
        }
    }

    private int resolve() {
        int resolved = 0;
        long now = System.currentTimeMillis();
        List<Entry> entries = store.list();
        int checked = 0;
        for (Entry e : entries) {
            // 복구 목록의 UNKNOWN만 대상이다. DLQ(DEAD)는 미전송이 확실한 실패라 조회할 이유가 없다.
            if (!"recovery".equals(e.list()) || !"UNKNOWN".equals(e.state().get("state"))) {
                continue;
            }
            if (now - e.preservedAtMs() < minAgeMs) {
                continue; // 방금 불명이 됐다 — Slack 조회에 아직 반영되지 않았을 수 있다
            }
            if (checked >= MAX_PER_CYCLE) {
                break; // 조회는 문맥 조회와 같은 Slack 한도(conversations.replies)를 쓴다 — 사이클당 상한을 둔다
            }
            checked++;
            // 건 단위로 격리한다: 한 건의 예외(락 타임아웃 등)가 뒤의 건들을 매 사이클 막지 않게.
            try {
                resolved += resolveOne(e);
            } catch (RuntimeException ex) {
                log.warn("결과 불명 자동 조회 실패(이 건만 건너뜀) event_id={} reason={}", e.eventId(),
                        ex.getClass().getSimpleName());
            }
        }
        return resolved;
    }

    private int resolveOne(Entry e) {
        Optional<ThreadRef> ref = store.threadRef(e.eventId());
        if (ref.isEmpty()) {
            return 0;
        }
        ScanResult scan = threads.findReplies(ref.get().channel(), ref.get().rootTs(), e.eventId());
        if (scan.failed() || scan.matches().isEmpty()) {
            // 조회 실패나 미발견은 "없다"는 뜻이 아니다 — 결과 불명을 유지한다.
            return 0;
        }
        var outcome = store.resolveCompletedAutomatically(e.eventId(), scan.matches().get(0).ts());
        if (outcome.ok()) {
            log.info("결과 불명 자동 조회: 답글 발견 → 완료 처리 event_id={}", e.eventId());
            return 1;
        }
        log.warn("결과 불명 자동 조회: 답글은 찾았으나 완료 처리가 거절됨 event_id={} status={}", e.eventId(), outcome.status());
        return 0;
    }
}
