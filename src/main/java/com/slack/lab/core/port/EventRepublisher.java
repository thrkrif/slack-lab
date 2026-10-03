package com.slack.lab.core.port;

import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.model.SlackMessageEvent;

/**
 * 이미 수락한 이벤트를 다음 세대로 큐에 다시 넣는다(재시도 재투입). 최초 수신 시각을 그대로 싣는다 — 재시도해도
 * 24시간 창과 답변 시간은 최초 수신 기준이다.
 */
public interface EventRepublisher {

    PublishResult republish(SlackMessageEvent event, long gen, long receivedAtMs);
}
