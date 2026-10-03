package com.slack.lab.adapter.alert;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.AlertEvent;
import com.slack.lab.core.port.AlertNormalizer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * CloudWatch 알람이 SNS(HTTP/S 구독)로 보낸 알림을 정규화한다. 바깥은 SNS 봉투, 안쪽 {@code Message}는 JSON 문자열이다.
 * "같은 알람"은 알람 이름 + 상태 변경 시각이다 — SNS가 같은 알림을 다시 보내도 같은 키이고, 해결(OK) 뒤 다시 ALARM이
 * 되면 상태 변경 시각이 달라 새 건이다. ALARM이 아닌 전이(OK, INSUFFICIENT_DATA)는 리포트 대상이 아니다.
 */
public class CloudWatchAlertNormalizer implements AlertNormalizer {

    private final ObjectMapper mapper;
    private final String channel;

    public CloudWatchAlertNormalizer(ObjectMapper mapper, String channel) {
        this.mapper = mapper;
        this.channel = channel;
    }

    @Override
    public String source() {
        return "cloudwatch";
    }

    @Override
    public Optional<AlertEvent> normalize(Map<String, String> headers, byte[] body) {
        JsonNode envelope = parse(body);
        String type = envelope.path("Type").asText("");
        if (!"Notification".equals(type)) {
            throw new IllegalArgumentException("not_a_notification:" + type);
        }
        JsonNode alarm = parse(envelope.path("Message").asText("").getBytes(StandardCharsets.UTF_8));
        String name = alarm.path("AlarmName").asText("");
        String changedAt = alarm.path("StateChangeTime").asText("");
        if (name.isBlank() || changedAt.isBlank()) {
            throw new IllegalArgumentException("missing_alarm_fields");
        }
        if (!"ALARM".equals(alarm.path("NewStateValue").asText(""))) {
            return Optional.empty();
        }
        Map<String, String> attrs = new LinkedHashMap<>();
        put(attrs, "region", alarm.path("Region").asText(""));
        put(attrs, "account", alarm.path("AWSAccountId").asText(""));
        put(attrs, "metric", alarm.path("Trigger").path("MetricName").asText(""));
        put(attrs, "namespace", alarm.path("Trigger").path("Namespace").asText(""));
        String text = cap(join(alarm.path("AlarmDescription").asText(""), alarm.path("NewStateReason").asText("")), 2000);
        String key = sha("cloudwatch|" + alarm.path("AWSAccountId").asText("") + "|" + alarm.path("Region").asText("") + "|"
                + name + "|" + changedAt);
        return Optional.of(new AlertEvent(source(), key, cap(name, 200), text, channel, attrs));
    }

    private JsonNode parse(byte[] json) {
        try {
            JsonNode n = mapper.readTree(json);
            if (n == null || !n.isObject()) {
                throw new IllegalArgumentException("not_json_object");
            }
            return n;
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("invalid_json");
        }
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (!v.isBlank()) {
            m.put(k, v);
        }
    }

    /** 큐·DB·LLM으로 가는 외부 입력의 크기를 제한한다. */
    private static String cap(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String join(String a, String b) {
        return a.isBlank() ? b : b.isBlank() ? a : a + "\n" + b;
    }

    /** 이벤트 ID에 원문(알람 이름 등)을 그대로 쓰지 않고 길이를 고정한다. 앞 16바이트면 충돌 걱정이 없다. */
    static String sha(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16; i++) {
                sb.append(String.format("%02x", d[i]));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
