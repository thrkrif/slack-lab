package com.slack.lab.slack;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(SlackEventController.class)
@Import(SlackSignatureVerifier.class)
@EnableConfigurationProperties(SlackProperties.class)
@TestPropertySource(properties = {"slack.signing-secret=ctrl-secret", "slack.bot-token=xoxb-test", "llm.model=m"})
class SlackEventControllerTest {

    @Autowired
    MockMvc mvc;

    static MockHttpServletRequestBuilder signed(String body) throws Exception {
        String ts = String.valueOf(Instant.now().getEpochSecond());
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec("ctrl-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = "v0=" + HexFormat.of().formatHex(mac.doFinal(("v0:" + ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
        return post("/slack/events").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-Slack-Request-Timestamp", ts).header("X-Slack-Signature", sig);
    }

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
    void url_verification은_challenge를_돌려준다() throws Exception {
        mvc.perform(signed("{\"type\":\"url_verification\",\"challenge\":\"abc123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").value("abc123"));
    }

    @Test
    void event_callback은_200을_반환한다() throws Exception {
        mvc.perform(signed("{\"type\":\"event_callback\",\"event_id\":\"Ev1\"}"))
                .andExpect(status().isOk()).andExpect(content().string(""));
    }
}
