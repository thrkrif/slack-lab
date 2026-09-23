package com.slack.lab.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SlackMessageEventTest {

    static SlackMessageEvent parse(String json) throws Exception {
        return SlackMessageEvent.from(new ObjectMapper().readTree(json));
    }

    static final String BASE = """
            {"event_id":"Ev1","authorizations":[{"user_id":"UBOT"}],
             "event":{"type":"app_mention","channel":"C1","user":"U1","ts":"100.1","text":"%s"%s}}""";

    static String payload(String text, String extra) {
        return BASE.formatted(text, extra);
    }

    @Test
    void 필드를_뽑는다() throws Exception {
        var e = parse(payload("<@UBOT> 안녕", ""));
        assertThat(e.eventId()).isEqualTo("Ev1");
        assertThat(e.channel()).isEqualTo("C1");
        assertThat(e.ts()).isEqualTo("100.1");
        assertThat(e.botUserId()).isEqualTo("UBOT");
        assertThat(e.shouldIgnore()).isFalse();
    }

    @Test
    void 봇_메시지와_subtype은_무시한다() throws Exception {
        assertThat(parse(payload("x", ",\"bot_id\":\"B1\"")).shouldIgnore()).isTrue();
        assertThat(parse(payload("x", ",\"subtype\":\"message_changed\"")).shouldIgnore()).isTrue();
    }

    @Test
    void 답글_스레드는_thread_ts가_있으면_그것을_쓴다() throws Exception {
        assertThat(parse(payload("x", "")).replyThreadTs()).isEqualTo("100.1");
        assertThat(parse(payload("x", ",\"thread_ts\":\"50.5\"")).replyThreadTs()).isEqualTo("50.5");
    }

    @Test
    void 봇_멘션만_지우고_다른_사람_멘션은_남긴다() throws Exception {
        assertThat(parse(payload("<@UBOT>   <@U2> 에게  뭐라고 할까", "")).promptText()).isEqualTo("<@U2> 에게 뭐라고 할까");
        assertThat(parse(payload("질문이야 <@UBOT>", "")).promptText()).isEqualTo("질문이야");
    }

    @Test
    void 봇_ID를_모르면_문장_앞_멘션만_지운다() throws Exception {
        var e = parse("""
                {"event_id":"Ev1","event":{"channel":"C1","ts":"1.1","text":"<@UBOT> <@U2> 안녕 <@U3>"}}""");
        assertThat(e.botUserId()).isNull();
        assertThat(e.promptText()).isEqualTo("안녕 <@U3>");
    }

    @Test
    void 필수_필드가_없으면_예외() {
        assertThatThrownBy(() -> parse("{\"event\":{\"channel\":\"C1\",\"ts\":\"1\"}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("event_id");
        assertThatThrownBy(() -> parse("{\"event_id\":\"E\",\"event\":{\"ts\":\"1\"}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channel");
    }

    @Test
    void text가_없어도_빈_프롬프트로_처리한다() throws Exception {
        var e = parse("{\"event_id\":\"E\",\"event\":{\"channel\":\"C\",\"ts\":\"1\"}}");
        assertThat(e.promptText()).isEmpty();
    }
}
