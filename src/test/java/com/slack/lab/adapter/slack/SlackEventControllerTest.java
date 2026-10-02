package com.slack.lab.adapter.slack;

import com.slack.lab.core.port.EventPublisher;
import com.slack.lab.config.SlackProperties;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slack.lab.core.model.PublishResult;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * M12부터 컨트롤러는 발행만 한다 — 핸들러·저장소는 워커 쪽이라 여기서 목킹하지 않는다(B2).
 * {@link EventPublisher}만 목으로 두고, 발행 결과별 HTTP 응답(200/503)을 확인한다.
 */
@WebMvcTest(SlackEventController.class)
@Import({SlackSignatureVerifier.class, AckLoggingFilter.class})
@EnableConfigurationProperties(SlackProperties.class)
@TestPropertySource(properties = {"slack.signing-secret=ctrl-secret", "slack.bot-token=xoxb-test", "llm.model=m"})
class SlackEventControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EventPublisher publisher;

    static MockHttpServletRequestBuilder signed(String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("ctrl-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "v0=" + HexFormat.of().formatHex(mac.doFinal(("v0:" + ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
        return post("/slack/events").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Slack-Request-Timestamp", ts).header("X-Slack-Signature", sig);
    }

    static final String VALID_EVENT = """
            {"type":"event_callback","event_id":"Ev1","event":{"channel":"C1","user":"U1","ts":"100.1","text":"<@UBOT> 안녕"}}""";

    @Test
    void 서명이_틀리면_401() throws Exception {
        mvc.perform(post("/slack/events").contentType(MediaType.APPLICATION_JSON).content("{\"type\":\"url_verification\",\"challenge\":\"c\"}")
                        .header("X-Slack-Request-Timestamp", String.valueOf(Instant.now().getEpochSecond()))
                        .header("X-Slack-Signature", "v0=bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 서명_헤더가_없으면_401() throws Exception {
        mvc.perform(post("/slack/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 서명은_유효하나_본문이_깨졌으면_400() throws Exception {
        mvc.perform(signed("{not json")).andExpect(status().isBadRequest());
    }

    @Test
    void 서명은_유효하나_type이_없으면_400() throws Exception {
        mvc.perform(signed("{\"foo\":1}")).andExpect(status().isBadRequest());
    }

    @Test
    void 필수_이벤트_필드가_없으면_400() throws Exception {
        // event 객체 없이 event_id만 있으면 SlackMessageEvent.from()이 거부한다.
        mvc.perform(signed("{\"type\":\"event_callback\",\"event_id\":\"Ev1\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void event_callback이_아닌_다른_type은_200으로_무시하고_발행하지_않는다() throws Exception {
        // app_uninstalled 등 처리 대상이 아닌 콜백까지 400으로 거절하면 Slack 재전송만 늘어난다.
        mvc.perform(signed("{\"type\":\"app_uninstalled\"}")).andExpect(status().isOk())
                .andExpect(content().string(""));
        verify(publisher, never()).publish(any(), anyLong(), any());
    }

    @Test
    void url_verification은_challenge를_돌려준다() throws Exception {
        mvc.perform(signed("{\"type\":\"url_verification\",\"challenge\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc123"));
    }

    @Test
    void bot_id가_있으면_무시하고_발행하지_않는다() throws Exception {
        // A5: bot_id·subtype 있는 이벤트는 200, LLM·발신 호출 0회(발행하지 않으므로 워커도 못 본다)
        String body = """
                {"type":"event_callback","event_id":"Ev1","event":{"channel":"C1","ts":"100.1","bot_id":"B1"}}""";
        mvc.perform(signed(body)).andExpect(status().isOk()).andExpect(content().string(""));
        verify(publisher, never()).publish(any(), anyLong(), any());
    }

    @Test
    void 큐_저장이_확인되면_200이고_중복_입력도_걸러내지_않는다() throws Exception {
        // ARCHITECTURE §3.1: 중복은 워커가 M11 선점 결과표로 억제한다. 컨트롤러는 항상 발행을 시도한다.
        when(publisher.publish(any(), anyLong(), any())).thenReturn(new PublishResult.Enqueued("1-0"));

        mvc.perform(signed(VALID_EVENT)).andExpect(status().isOk()).andExpect(content().string(""));
        verify(publisher).publish(any(), anyLong(), any());
    }

    @Test
    void 큐_저장_실패는_503이다() throws Exception {
        when(publisher.publish(any(), anyLong(), any())).thenReturn(new PublishResult.Failed("enqueue_failed:x"));

        mvc.perform(signed(VALID_EVENT)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void 큐_저장_확인_불가도_503이다() throws Exception {
        // WAITAOF로 확인받지 못하면 저장 여부가 불명확하므로 200을 주지 않는다(B1).
        when(publisher.publish(any(), anyLong(), any())).thenReturn(new PublishResult.Unconfirmed("waitaof_numlocal_0"));

        mvc.perform(signed(VALID_EVENT)).andExpect(status().isServiceUnavailable());
    }

    @Test
    void 발행_호출에_최상위_필터가_잡은_수신_시각이_전달된다() throws Exception {
        when(publisher.publish(any(), anyLong(), any())).thenReturn(new PublishResult.Enqueued("1-0"));

        long before = System.currentTimeMillis();
        mvc.perform(signed(VALID_EVENT)).andExpect(status().isOk());
        long after = System.currentTimeMillis();

        var captor = org.mockito.ArgumentCaptor.forClass(Long.class);
        verify(publisher).publish(any(), captor.capture(), any());
        assertThatReceivedAtIsWithin(captor.getValue(), before, after);
    }

    private static void assertThatReceivedAtIsWithin(long receivedAtMs, long before, long after) {
        org.assertj.core.api.Assertions.assertThat(receivedAtMs).isBetween(before, after);
    }
}
