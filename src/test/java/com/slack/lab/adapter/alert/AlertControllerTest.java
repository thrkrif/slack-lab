package com.slack.lab.adapter.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AlertProperties;
import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.EventPublisher;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AlertController.class)
@Import(AlertControllerTest.Config.class)
class AlertControllerTest {

    @TestConfiguration
    static class Config {
        @Bean
        AlertProperties alertProperties() {
            return new AlertProperties("s3cret", "C-ALERT");
        }

        @Bean
        CloudWatchAlertNormalizer normalizer() {
            return new CloudWatchAlertNormalizer(new ObjectMapper(), "C-ALERT");
        }
    }

    @Autowired
    MockMvc mvc;

    @MockitoBean
    EventPublisher publisher;

    static String body(String state) throws Exception {
        ObjectMapper m = new ObjectMapper();
        String msg = m.writeValueAsString(Map.of("AlarmName", "web-cpu", "NewStateValue", state, "StateChangeTime", "t1"));
        return m.writeValueAsString(Map.of("Type", "Notification", "Message", msg));
    }

    @Test
    void 시크릿이_없거나_틀리면_401이고_발행하지_않는다() throws Exception {
        mvc.perform(post("/alerts/cloudwatch").contentType(MediaType.APPLICATION_JSON).content(body("ALARM")))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/alerts/cloudwatch?token=wrong").contentType(MediaType.APPLICATION_JSON).content(body("ALARM")))
                .andExpect(status().isUnauthorized());
        verify(publisher, never()).publish(any(), anyLong(), any());
    }

    @Test
    void 모르는_원천은_404다() throws Exception {
        mvc.perform(post("/alerts/grafana?token=s3cret").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void 쿼리_토큰이나_헤더_시크릿으로_인증하고_큐_저장_뒤에만_200이다() throws Exception {
        given(publisher.publish(any(SlackMessageEvent.class), anyLong(), any())).willReturn(new PublishResult.Enqueued("m1"));

        mvc.perform(post("/alerts/cloudwatch?token=s3cret").contentType(MediaType.APPLICATION_JSON).content(body("ALARM")))
                .andExpect(status().isOk());
        mvc.perform(post("/alerts/cloudwatch").header("X-Alert-Secret", "s3cret").contentType(MediaType.APPLICATION_JSON)
                .content(body("ALARM"))).andExpect(status().isOk());
        verify(publisher, org.mockito.Mockito.times(2)).publish(any(SlackMessageEvent.class), anyLong(), eq(null));
    }

    @Test
    void 저장을_확인하지_못하면_503으로_재전송을_유도한다() throws Exception {
        given(publisher.publish(any(SlackMessageEvent.class), anyLong(), any())).willReturn(new PublishResult.Failed("x"));
        mvc.perform(post("/alerts/cloudwatch?token=s3cret").contentType(MediaType.APPLICATION_JSON).content(body("ALARM")))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void 해결_전이는_200으로_받되_발행하지_않고_깨진_본문은_400이다() throws Exception {
        mvc.perform(post("/alerts/cloudwatch?token=s3cret").contentType(MediaType.APPLICATION_JSON).content(body("OK")))
                .andExpect(status().isOk());
        mvc.perform(post("/alerts/cloudwatch?token=s3cret").contentType(MediaType.APPLICATION_JSON).content("nope"))
                .andExpect(status().isBadRequest());
        verify(publisher, never()).publish(any(), anyLong(), any());
    }

    @Test
    void 저장_확인_불가도_503이고_빈_헤더가_유효한_토큰을_가리지_않는다() throws Exception {
        given(publisher.publish(any(SlackMessageEvent.class), anyLong(), any())).willReturn(new PublishResult.Unconfirmed("t"));
        mvc.perform(post("/alerts/cloudwatch?token=s3cret").header("X-Alert-Secret", "")
                .contentType(MediaType.APPLICATION_JSON).content(body("ALARM"))).andExpect(status().isServiceUnavailable());
    }

    @Test
    void SNS_구독_확인은_200으로_받고_발행하지_않는다() throws Exception {
        mvc.perform(post("/alerts/cloudwatch?token=s3cret").contentType(MediaType.APPLICATION_JSON)
                .content("{\"Type\":\"SubscriptionConfirmation\",\"SubscribeURL\":\"https://sns.ap-northeast-2.amazonaws.com/?Action=ConfirmSubscription\"}"))
                .andExpect(status().isOk());
        verify(publisher, never()).publish(any(), anyLong(), any());
    }
}
