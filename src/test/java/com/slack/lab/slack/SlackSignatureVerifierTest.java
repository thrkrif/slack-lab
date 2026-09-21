package com.slack.lab.slack;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class SlackSignatureVerifierTest {

    static final String SECRET = "test-secret";
    static final long NOW = 1_700_000_000L;
    static final String BODY = "{\"type\":\"event_callback\",\"text\":\"한글 \\u003c포함\\u003e\"}";

    final SlackSignatureVerifier verifier = verifier(SECRET);

    static SlackSignatureVerifier verifier(String secret) {
        var props = new SlackProperties(secret, "xoxb-test", "https://slack.com/api", 10_000);
        return new SlackSignatureVerifier(props, Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC));
    }

    static String sign(String secret, String ts, String body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "v0=" + HexFormat.of().formatHex(mac.doFinal(("v0:" + ts + ":" + body).getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void 올바른_서명은_통과한다() throws Exception {
        String ts = String.valueOf(NOW);
        assertThat(verifier.verify(ts, sign(SECRET, ts, BODY), BODY)).isTrue();
    }

    @Test
    void 본문이_한_글자라도_바뀌면_거부한다() throws Exception {
        String ts = String.valueOf(NOW);
        assertThat(verifier.verify(ts, sign(SECRET, ts, BODY), BODY + " ")).isFalse();
    }

    @Test
    void 다른_시크릿으로_만든_서명은_거부한다() throws Exception {
        String ts = String.valueOf(NOW);
        assertThat(verifier.verify(ts, sign("other", ts, BODY), BODY)).isFalse();
    }

    @Test
    void 허용_시간을_넘긴_요청은_서명이_맞아도_거부한다() throws Exception {
        String old = String.valueOf(NOW - 301);
        assertThat(verifier.verify(old, sign(SECRET, old, BODY), BODY)).isFalse();
        String edge = String.valueOf(NOW - 300);
        assertThat(verifier.verify(edge, sign(SECRET, edge, BODY), BODY)).isTrue();
    }

    @Test
    void 미래_타임스탬프도_같은_허용_범위를_적용한다() throws Exception {
        String future = String.valueOf(NOW + 301);
        assertThat(verifier.verify(future, sign(SECRET, future, BODY), BODY)).isFalse();
    }

    @Test
    void 헤더_누락과_숫자가_아닌_타임스탬프는_예외_없이_거부한다() {
        assertThat(verifier.verify(null, "v0=abc", BODY)).isFalse();
        assertThat(verifier.verify(String.valueOf(NOW), null, BODY)).isFalse();
        assertThat(verifier.verify("abc", "v0=abc", BODY)).isFalse();
    }

    @Test
    void 빈_시크릿이면_모든_요청을_거부한다() throws Exception {
        String ts = String.valueOf(NOW);
        // 빈 키로는 HMAC을 만들 수 없으므로 임의 서명으로 확인한다.
        assertThat(verifier("").verify(ts, "v0=" + "0".repeat(64), BODY)).isFalse();
    }
}
