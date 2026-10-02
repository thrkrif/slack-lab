package com.slack.lab.core.service;

import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.port.EventRepublisher;
import com.slack.lab.core.port.RetryOutbox;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 도래한 재시도를 큐에 다시 넣는다(M21). 발신함에서 읽어 재발행하고, 큐가 확인하면 반영 시각을 기록한다. 주기 실행은
 * 호출자(어댑터 배선)가 맡는다 — 이 클래스는 한 번의 사이클만 안다.
 *
 * <p>순서가 안전한 이유: 재발행이 확인되기 전에 죽으면 반영 시각이 없어 다음 사이클이 다시 넣고, 확인된 뒤 반영 기록 전에
 * 죽으면 같은 메시지가 두 번 들어갈 수 있지만 선점 결과표가 하나만 실행시킨다. 어느 쪽도 입력이 사라지지 않는다.
 */
public class RetryRelay {

    private static final Logger log = LoggerFactory.getLogger(RetryRelay.class);

    private final RetryOutbox outbox;
    private final EventRepublisher republisher;
    private final int batchLimit;
    private final long republishAfterMs;

    public RetryRelay(RetryOutbox outbox, EventRepublisher republisher, int batchLimit, long republishAfterMs) {
        this.outbox = outbox;
        this.republisher = republisher;
        this.batchLimit = batchLimit;
        this.republishAfterMs = republishAfterMs;
    }

    /** @return 이번 사이클에 큐가 확인한 재투입 건수 */
    public int runOnce() {
        // 한 사이클의 예외가 주기 실행(scheduleWithFixedDelay)을 영구히 멈추지 않게 여기서 삼킨다 — 멈추면 재시도가 조용히 사라진다.
        try {
            return relay();
        } catch (RuntimeException e) {
            log.error("재시도 릴레이 사이클 오류 — 다음 사이클에 다시 시도", e);
            return 0;
        }
    }

    private int relay() {
        int relayed = 0;
        List<RetryOutbox.DueRetry> due = outbox.pollDueRetries(batchLimit, republishAfterMs);
        for (RetryOutbox.DueRetry d : due) {
            PublishResult result = republisher.republish(d.event(), d.gen(), d.receivedAtMs());
            if (result instanceof PublishResult.Enqueued) {
                outbox.markRelayed(d.event().eventId(), d.gen());
                relayed++;
            } else {
                // 반영 시각을 남기지 않았으니 다음 사이클이 다시 시도한다.
                log.warn("재시도 재투입 실패 — 다음 사이클에 다시 시도 event_id={} gen={} result={}", d.event().eventId(),
                        d.gen(), result.getClass().getSimpleName());
            }
        }
        if (relayed > 0) {
            log.info("재시도 재투입 count={}", relayed);
        }
        return relayed;
    }
}
