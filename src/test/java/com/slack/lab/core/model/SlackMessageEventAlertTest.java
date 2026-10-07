package com.slack.lab.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SlackMessageEventAlertTest {

    @Test
    void 알람_이벤트는_ts와_threadTs가_없다() {
        var alert = new AlertEvent("sns", "alarm-1", "제목", "본문", "C-1", java.util.Map.of()).toMessageEvent();
        assertThat(alert.isAlert()).isTrue();
    }

    @Test
    void 슬랙_멘션은_알람이_아니다() {
        var mention = new SlackMessageEvent("Ev1", "C1", "U1", "<@B> 질문", "1.1", null, null, null, "B");
        var inThread = new SlackMessageEvent("Ev2", "C1", "U1", "<@B> 질문", "1.2", "1.1", null, null, "B");
        assertThat(mention.isAlert()).isFalse();
        assertThat(inThread.isAlert()).isFalse();
    }
}
