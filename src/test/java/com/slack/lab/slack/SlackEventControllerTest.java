package com.slack.lab.slack;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.slack.lab.event.AttemptHandle;
import com.slack.lab.event.ClaimResult;
import com.slack.lab.event.EventDeduplicator;
import com.slack.lab.event.HandlingResult;
import com.slack.lab.event.ProcessingState;
import com.slack.lab.event.SlackEventHandler;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SlackEventController.class)
@Import(SlackSignatureVerifier.class)
@EnableConfigurationProperties(SlackProperties.class)
@TestPropertySource(properties = {"slack.signing-secret=ctrl-secret", "slack.bot-token=xoxb-test", "llm.model=m"})
class SlackEventControllerTest {

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EventDeduplicator deduplicator;

    @MockitoBean
    SlackEventHandler handler;

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
    void event_callback이_아닌_다른_type은_200으로_무시하고_핸들러를_호출하지_않는다() throws Exception {
        // app_uninstalled 등 처리 대상이 아닌 콜백까지 400으로 거절하면 Slack 재전송만 늘어난다.
        mvc.perform(signed("{\"type\":\"app_uninstalled\"}")).andExpect(status().isOk())
                .andExpect(content().string(""));
        verify(deduplicator, never()).claim(any());
        verify(handler, never()).handle(any(), any());
    }

    @Test
    void url_verification은_challenge를_돌려준다() throws Exception {
        mvc.perform(signed("{\"type\":\"url_verification\",\"challenge\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc123"));
    }

    @Test
    void bot_id가_있으면_무시하고_핸들러를_호출하지_않는다() throws Exception {
        // A5: bot_id·subtype 있는 이벤트는 200, LLM·발신 호출 0회(=핸들러 미호출로 보장)
        String body = """
                {"type":"event_callback","event_id":"Ev1","event":{"channel":"C1","ts":"100.1","bot_id":"B1"}}""";
        mvc.perform(signed(body)).andExpect(status().isOk()).andExpect(content().string(""));
        verify(deduplicator, never()).claim(any());
        verify(handler, never()).handle(any(), any());
    }

    @Test
    void 중복_이벤트는_핸들러를_호출하지_않고_200이다() throws Exception {
        // A9의 컨트롤러 쪽 절반: 선점 실패는 200 즉시 반환
        when(deduplicator.claim(eq("Ev1"))).thenReturn(new ClaimResult.Duplicate(ProcessingState.PROCESSING));
        mvc.perform(signed(VALID_EVENT)).andExpect(status().isOk()).andExpect(content().string(""));
        verify(handler, never()).handle(any(), any());
    }

    @Test
    void 새_이벤트는_핸들러를_호출하고_결과와_무관하게_200이다() throws Exception {
        AttemptHandle attempt = org.mockito.Mockito.mock(AttemptHandle.class);
        when(attempt.attemptId()).thenReturn("A1");
        when(deduplicator.claim(eq("Ev1"))).thenReturn(new ClaimResult.Claimed(attempt));
        when(handler.handle(any(), eq(attempt))).thenReturn(new HandlingResult.Failed("llm_failed:x"));

        mvc.perform(signed(VALID_EVENT)).andExpect(status().isOk()).andExpect(content().string(""));
        verify(handler).handle(any(), eq(attempt));
    }

    @Test
    void 처리_권한_확보_후_핸들러_예외는_200을_유지한다() throws Exception {
        // "처리 권한 확보 후 예외는 200 유지·로그" (M6)
        AttemptHandle attempt = org.mockito.Mockito.mock(AttemptHandle.class);
        when(deduplicator.claim(eq("Ev1"))).thenReturn(new ClaimResult.Claimed(attempt));
        when(handler.handle(any(), eq(attempt))).thenThrow(new RuntimeException("예기치 못한 오류"));

        mvc.perform(signed(VALID_EVENT)).andExpect(status().isOk());
    }
}
