package com.slack.lab.core.port;

import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.model.SlackMessageEvent;

/**
 * 수신 이벤트를 내구성 있게 저장한다(P1-1). 저장이 확인된 경우에만 {@code Enqueued}를 돌려주고, 그때만 수신 서버가
 * 200을 준다. 어떤 브로커를 쓰는지는 어댑터의 몫이다.
 */
public interface EventPublisher {

    /** 첫 발행은 gen 0이다. 재시도·재처리 투입은 이 포트의 책임이 아니다. */
    PublishResult publish(SlackMessageEvent event, long receivedAtMs, String retryNum);
}
