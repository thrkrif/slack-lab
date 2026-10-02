package com.slack.lab.core.port;

import com.slack.lab.core.model.SlackMessageEvent;
import java.util.List;

/**
 * 재시도 발신함. 재시도가 예약되면(상태 {@code RETRY_WAIT} + 보존 입력) 상태 저장소가 이 발신함에 입력을 남기고, 릴레이가
 * 도래한 건을 큐에 다시 넣는다. 브로커와 DB를 한 트랜잭션으로 묶을 수 없으므로 "먼저 상태에 기록하고, 나중에 큐에 반영한다"를
 * 되풀이 가능하게 만든 것이다 — 중복 재투입은 선점 결과표(BUSY·STALE·DONE)가 하나만 실행시킨다.
 */
public interface RetryOutbox {

    /** 재투입할 건. {@code gen}은 다음 세대 번호다. */
    record DueRetry(SlackMessageEvent event, long gen, long receivedAtMs) {}

    /**
     * 도래한 재시도(상태가 아직 그 세대의 {@code RETRY_WAIT}인 것만). 이미 재투입했더라도 {@code republishAfterMs}가
     * 지나도록 선점되지 않았으면 다시 돌려준다(재투입 메시지가 유실됐을 수 있다).
     */
    List<DueRetry> pollDueRetries(int limit, long republishAfterMs);

    /** 재투입을 큐가 확인했음을 기록한다. 다음 폴링은 {@code republishAfterMs} 동안 이 건을 건너뛴다. */
    void markRelayed(String eventId, long gen);
}
