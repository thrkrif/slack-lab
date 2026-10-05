package com.slack.lab.adapter.rabbitmq;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.SlackMessageEvent;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class EventMessageJsonTest {

    final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 큐를_건너도_우리_봇_사용자_ID가_보존된다() {
        var e = new SlackMessageEvent("Ev1", "C1", "U1", "질문", "100.1", "100.0", null, null, "UBOT");

        var parsed = EventMessageJson.read(mapper, EventMessageJson.write(mapper, e, 1, 123L, null));

        assertThat(parsed.event().botUserId()).isEqualTo("UBOT");
        assertThat(parsed.event().eventId()).isEqualTo("Ev1");
    }

    @Test
    void 봇_사용자_ID가_없으면_없는_채로_돌아온다_알람_이벤트() {
        var e = new SlackMessageEvent("alert-x", "C1", "alert:cloudwatch", "본문", null, null, null, null, null);

        var parsed = EventMessageJson.read(mapper, EventMessageJson.write(mapper, e, 1, 123L, null));

        assertThat(parsed.event().botUserId()).isNull();
    }

    @Test
    void 이전_형식의_메시지는_필드가_없어도_읽힌다() {
        byte[] old = "{\"event_id\":\"Ev1\",\"channel\":\"C1\",\"user\":\"U1\",\"text\":\"q\",\"ts\":\"1.1\",\"gen\":0,\"received_at\":5}"
                .getBytes(StandardCharsets.UTF_8);

        var parsed = EventMessageJson.read(mapper, old);

        assertThat(parsed).isNotNull();
        assertThat(parsed.event().botUserId()).isNull();
    }
}
