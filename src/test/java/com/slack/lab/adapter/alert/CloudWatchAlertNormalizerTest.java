package com.slack.lab.adapter.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.AlertEvent;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CloudWatchAlertNormalizerTest {

    static final ObjectMapper MAPPER = new ObjectMapper();
    final CloudWatchAlertNormalizer normalizer = new CloudWatchAlertNormalizer(MAPPER, "C-ALERT");

    static byte[] sns(String type, String state, String name, String changedAt) throws Exception {
        String message = MAPPER.writeValueAsString(Map.of("AlarmName", name, "NewStateValue", state, "StateChangeTime",
                changedAt, "NewStateReason", "임계치 초과", "AlarmDescription", "CPU 높음", "Region", "ap-northeast-2",
                "Trigger", Map.of("MetricName", "CPUUtilization", "Namespace", "AWS/EC2")));
        return MAPPER.writeValueAsBytes(Map.of("Type", type, "Message", message));
    }

    @Test
    void ALARM_전이는_채널과_본문이_채워진_알람이_된다() throws Exception {
        AlertEvent e = normalizer.normalize(Map.of(), sns("Notification", "ALARM", "web-cpu", "2026-10-02T01:00:00.000+0000"))
                .orElseThrow();

        assertThat(e.channel()).isEqualTo("C-ALERT");
        assertThat(e.title()).isEqualTo("web-cpu");
        assertThat(e.text()).contains("CPU 높음").contains("임계치 초과");
        assertThat(e.attributes()).containsEntry("metric", "CPUUtilization").containsEntry("region", "ap-northeast-2");
    }

    @Test
    void 같은_회차는_같은_키이고_재발은_다른_키이며_알람_이름만_달라도_다르다() throws Exception {
        String k1 = key("web-cpu", "2026-10-02T01:00:00.000+0000");
        assertThat(key("web-cpu", "2026-10-02T01:00:00.000+0000")).isEqualTo(k1);
        assertThat(key("web-cpu", "2026-10-02T03:00:00.000+0000")).isNotEqualTo(k1);
        assertThat(key("web-mem", "2026-10-02T01:00:00.000+0000")).isNotEqualTo(k1);
    }

    private String key(String name, String at) throws Exception {
        return normalizer.normalize(Map.of(), sns("Notification", "ALARM", name, at)).orElseThrow().dedupKey();
    }

    /**
     * scripts/alert-roundtrip이 이 공식으로 이벤트 ID를 다시 계산해 봇 답글의 메타데이터와 맞춘다 — 공식이 바뀌면 스크립트가 조용히
     * 어긋나므로 고정한다: "alert-" + SHA-256("cloudwatch|계정|리전|알람 이름|상태 변경 시각")의 앞 16바이트(hex 32자).
     */
    @Test
    void 이벤트_ID_공식은_알람_왕복_스크립트가_의존하므로_고정한다() throws Exception {
        byte[] body = MAPPER.writeValueAsBytes(Map.of("Type", "Notification", "Message", MAPPER.writeValueAsString(Map.of(
                "AlarmName", "payment-api-x", "NewStateValue", "ALARM", "StateChangeTime", "2026-10-06T00:00:00.000+0000",
                "Region", "ap-northeast-2", "AWSAccountId", "000000000000", "Trigger", Map.of()))));

        AlertEvent e = normalizer.normalize(Map.of(), body).orElseThrow();

        // python3: "alert-" + hashlib.sha256(b"cloudwatch|000000000000|ap-northeast-2|payment-api-x|2026-10-06T00:00:00.000+0000").hexdigest()[:32]
        assertThat(e.toMessageEvent().eventId()).isEqualTo("alert-aa5a5dc477c74556603fe5a1bc5c581a");
    }

    @Test
    void ALARM이_아닌_전이는_리포트_대상이_아니다() throws Exception {
        assertThat(normalizer.normalize(Map.of(), sns("Notification", "OK", "web-cpu", "2026-10-02T02:00:00.000+0000")))
                .isEmpty();
    }

    @Test
    void 알림이_아니거나_깨진_본문은_거절한다() throws Exception {
        assertThatThrownBy(() -> normalizer.normalize(Map.of(), sns("UnsubscribeConfirmation", "ALARM", "a", "t")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize(Map.of(), "not json".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> normalizer.normalize(Map.of(),
                MAPPER.writeValueAsBytes(Map.of("Type", "Notification", "Message", "{}"))))
                .hasMessageContaining("missing_alarm_fields");
    }

    @Test
    void 메시지_이벤트로_바뀌면_원_메시지_없이_결정적_이벤트_ID를_갖는다() throws Exception {
        AlertEvent e = normalizer.normalize(Map.of(), sns("Notification", "ALARM", "web-cpu", "t1")).orElseThrow();
        var m = e.toMessageEvent();

        assertThat(m.ts()).isNull();
        assertThat(m.replyThreadTs()).isNull();
        assertThat(m.shouldIgnore()).isFalse();
        assertThat(m.eventId()).isEqualTo("alert-" + e.dedupKey()).doesNotContain("web-cpu");
        assertThat(m.text()).contains("web-cpu");
    }

    @Test
    void 본문은_길이가_제한되고_계정이나_리전이_다르면_다른_키다() throws Exception {
        String longDesc = "x".repeat(10_000);
        String msg = MAPPER.writeValueAsString(Map.of("AlarmName", "a", "NewStateValue", "ALARM", "StateChangeTime", "t",
                "AlarmDescription", longDesc, "Region", "us-east-1"));
        AlertEvent e = normalizer.normalize(Map.of(), MAPPER.writeValueAsBytes(Map.of("Type", "Notification", "Message", msg)))
                .orElseThrow();
        assertThat(e.text().length()).isLessThanOrEqualTo(2000);

        String other = MAPPER.writeValueAsString(Map.of("AlarmName", "a", "NewStateValue", "ALARM", "StateChangeTime", "t",
                "Region", "eu-west-1"));
        assertThat(normalizer.normalize(Map.of(), MAPPER.writeValueAsBytes(Map.of("Type", "Notification", "Message", other)))
                .orElseThrow().dedupKey()).isNotEqualTo(e.dedupKey());
    }
}
