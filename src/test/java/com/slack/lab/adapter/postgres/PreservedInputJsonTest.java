package com.slack.lab.adapter.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.SlackMessageEvent;
import org.junit.jupiter.api.Test;

class PreservedInputJsonTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 재처리용_보존_입력에도_우리_봇_사용자_ID가_남고_세대를_바꿔도_유지된다() {
        var e = new SlackMessageEvent("Ev1", "C1", "U1", "질문", "100.1", "100.0", null, null, "UBOT");

        String json = PreservedInputJson.write(mapper, e, 0, 123L);
        String regen = PreservedInputJson.withGen(mapper, json, 1);

        assertThat(PreservedInputJson.readEvent(mapper, json).botUserId()).isEqualTo("UBOT");
        assertThat(PreservedInputJson.readEvent(mapper, regen).botUserId()).isEqualTo("UBOT");
        assertThat(PreservedInputJson.readGen(mapper, regen)).isEqualTo(1);
    }

    @Test
    void 이전_형식_보존_입력과_알람_이벤트는_null로_읽힌다() {
        String old = "{\"event_id\":\"Ev1\",\"channel\":\"C1\",\"user\":\"U1\",\"text\":\"q\",\"ts\":\"1.1\",\"thread_ts\":null,\"gen\":0,\"received_at\":5}";
        var alert = new SlackMessageEvent("alert-x", "C1", "alert:cloudwatch", "본문", null, null, null, null, null);

        assertThat(PreservedInputJson.readEvent(mapper, old).botUserId()).isNull();
        assertThat(PreservedInputJson.readEvent(mapper, PreservedInputJson.write(mapper, alert, 0, 1L)).botUserId()).isNull();
    }
}
