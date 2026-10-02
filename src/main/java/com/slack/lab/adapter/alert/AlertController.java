package com.slack.lab.adapter.alert;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AlertProperties;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.core.model.AlertEvent;
import com.slack.lab.core.model.PublishResult;
import com.slack.lab.core.port.AlertNormalizer;
import com.slack.lab.core.port.EventPublisher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 모니터링 도구가 직접 보내는 알람의 입구(M23, ADR-9). Slack에 올라온 알람 메시지를 파싱하지 않는다. 인증은 공유 시크릿
 * (헤더 {@code X-Alert-Secret} 또는 SNS처럼 헤더를 못 쓰는 원천용 쿼리 {@code token})이다. 처리는 Slack 이벤트와 같이
 * 큐 저장 확인 후에만 200이고, 중복 억제는 워커의 선점이 한다.
 *
 * <p>SNS 서명 검증은 하지 않는다 — 시크릿이 인증을 대신한다(HTTPS 전제, URL에 시크릿이 들어가므로 접근 로그에 주의).
 * 구독 확인(SubscriptionConfirmation)은 SubscribeURL을 서버가 대신 호출하지 않는다(SSRF 방지) — 운영자가 로그의 URL로
 * 한 번 확인한다(SubscribeURL은 https의 sns.<region>.amazonaws.com일 때만 로그에 남긴다).
 */
@RestController
@ConditionalOnRole({AppRole.RECEIVER, AppRole.ALL})
public class AlertController {

    private static final Logger log = LoggerFactory.getLogger(AlertController.class);

    private final AlertProperties props;
    private final EventPublisher publisher;
    private final Map<String, AlertNormalizer> normalizers;
    private final ObjectMapper mapper;

    public AlertController(AlertProperties props, EventPublisher publisher, List<AlertNormalizer> normalizers,
            ObjectMapper mapper) {
        this.props = props;
        this.publisher = publisher;
        this.normalizers = new java.util.HashMap<>();
        normalizers.forEach(n -> this.normalizers.put(n.source(), n));
        this.mapper = mapper;
    }

    @PostMapping("/alerts/{source}")
    public ResponseEntity<?> receive(@PathVariable String source, @RequestBody byte[] body,
            @RequestHeader(value = "X-Alert-Secret", required = false) String headerSecret,
            @RequestParam(value = "token", required = false) String tokenParam) {
        AlertNormalizer normalizer = normalizers.get(source);
        if (!props.enabled() || normalizer == null) {
            return ResponseEntity.notFound().build(); // 꺼져 있거나 모르는 원천 — 존재를 드러내지 않는다
        }
        String given = headerSecret != null && !headerSecret.isEmpty() ? headerSecret : tokenParam;
        if (!secretMatches(given)) {
            log.warn("알람 인증 실패 → 401 source={}", source);
            return ResponseEntity.status(401).build();
        }
        String confirmUrl = subscriptionConfirmUrl(body);
        if (confirmUrl != null) {
            // 서버가 대신 열지 않는다(SSRF). 운영자가 이 URL을 한 번 열어 구독을 확인한다.
            log.warn("SNS 구독 확인이 필요하다 — 운영자가 이 URL을 한 번 연다 subscribe_url={}", confirmUrl);
            return ResponseEntity.ok().build();
        }

        Optional<AlertEvent> alert;
        try {
            alert = normalizer.normalize(Map.of(), body);
        } catch (IllegalArgumentException e) {
            log.warn("알람 정규화 실패 → 400 source={} reason={}", source, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
        if (alert.isEmpty()) {
            log.info("처리 대상 아닌 알람 전이 무시 source={}", source);
            return ResponseEntity.ok().build();
        }

        var event = alert.get().toMessageEvent();
        PublishResult result = publisher.publish(event, System.currentTimeMillis(), null);
        return switch (result) {
            case PublishResult.Enqueued e -> {
                log.info("알람 발행 완료 event_id={} source={}", event.eventId(), source);
                yield ResponseEntity.ok().build();
            }
            // 저장을 확인하지 못했으면 503 — SNS가 재전송하고, 같은 알람은 선점이 흡수한다.
            case PublishResult.Failed f -> {
                log.error("알람 발행 실패 event_id={} reason={}", event.eventId(), f.reason());
                yield ResponseEntity.status(503).build();
            }
            case PublishResult.Unconfirmed u -> {
                log.error("알람 발행 확인 불가 event_id={} reason={}", event.eventId(), u.reason());
                yield ResponseEntity.status(503).build();
            }
        };
    }

    private boolean secretMatches(String given) {
        if (given == null) {
            return false;
        }
        return MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                props.secret().getBytes(StandardCharsets.UTF_8));
    }

    /** 구독 확인 요청이면 안전한 SubscribeURL(https, sns.<region>.amazonaws.com)을 돌려준다. 아니면 null. */
    private String subscriptionConfirmUrl(byte[] body) {
        try {
            var n = mapper.readTree(body);
            if (!"SubscriptionConfirmation".equals(n.path("Type").asText(""))) {
                return null;
            }
            String url = n.path("SubscribeURL").asText("");
            java.net.URI u = java.net.URI.create(url);
            boolean safe = "https".equals(u.getScheme()) && u.getHost() != null
                    && u.getHost().matches("sns\\.[a-z0-9-]+\\.amazonaws\\.com(\\.cn)?");
            return safe ? url : "(허용되지 않는 SubscribeURL — 무시)";
        } catch (java.io.IOException | IllegalArgumentException e) {
            return null;
        }
    }
}
