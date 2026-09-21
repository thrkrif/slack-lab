package com.slack.lab.slack;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class SlackSignatureVerifier {

    // 리플레이 방어. Slack 권고값이며 이보다 오래된 요청은 서명이 맞아도 거부한다.
    static final long MAX_AGE_SECONDS = 300;

    private final byte[] secret;
    private final Clock clock;

    @Autowired
    public SlackSignatureVerifier(SlackProperties props) {
        this(props, Clock.systemUTC());
    }

    SlackSignatureVerifier(SlackProperties props, Clock clock) {
        this.secret = props.signingSecret().getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    /** 예외를 던지지 않고 거부 여부만 돌려준다. 어떤 헤더 결함이든 호출자에게는 401 하나다. */
    public boolean verify(String timestamp, String signature, String rawBody) {
        if (timestamp == null || signature == null || rawBody == null || secret.length == 0) {
            return false;
        }
        long ts;
        try {
            ts = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        if (Math.abs(clock.instant().getEpochSecond() - ts) > MAX_AGE_SECONDS) {
            return false;
        }
        // 파싱·재직렬화한 본문이 아니라 수신 그대로의 문자열로 계산해야 서명이 맞는다.
        String expected = "v0=" + hmacHex("v0:" + timestamp + ":" + rawBody);
        // 문자열 equals는 불일치 위치에 따라 시간이 달라져 서명을 한 글자씩 추측당할 수 있다.
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }

    private String hmacHex(String base) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(base.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HmacSHA256 사용 불가", e);
        }
    }
}
