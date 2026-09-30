package com.slack.lab.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.event.SlackMessageEvent;
import com.slack.lab.queue.EventPublisher;
import com.slack.lab.queue.PublishResult;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * ARCHITECTURE §3.1 표대로 HTTP 응답을 결정한다(M12부터: 큐 저장 확인 후에만 200). 처리는 워커의 몫이라
 * 이 컨트롤러는 서명 검증·필터링·발행만 한다 — LLM도 Slack 발신도 호출하지 않는다(B2).
 */
@RestController
@ConditionalOnRole({AppRole.RECEIVER, AppRole.ALL})
public class SlackEventController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventController.class);

    private final SlackSignatureVerifier verifier;
    private final ObjectMapper mapper;
    private final EventPublisher publisher;

    public SlackEventController(SlackSignatureVerifier verifier, ObjectMapper mapper, EventPublisher publisher) {
        this.verifier = verifier;
        this.mapper = mapper;
        this.publisher = publisher;
    }

    @PostMapping("/slack/events")
    public ResponseEntity<?> receive(
            // 서명 계산에 raw 문자열이 필요하므로 객체 바인딩을 쓰지 않는다.
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestHeader(value = "X-Slack-Retry-Num", required = false) String retryNum,
            @RequestHeader(value = "X-Slack-Retry-Reason", required = false) String retryReason,
            HttpServletRequest servletRequest) {

        // 재전송 관측(P0-7)은 검증 결과와 무관하게 첫 줄에서 남긴다.
        log.info("slack 수신 retry_num={} retry_reason={}", retryNum, retryReason);

        if (!verifier.verify(timestamp, signature, rawBody)) {
            log.warn("서명 검증 실패 → 401 (처리하지 않음)");
            return ResponseEntity.status(401).build();
        }

        JsonNode root;
        try {
            root = mapper.readTree(rawBody);
        } catch (Exception e) {
            // 본문은 사용자 대화를 담을 수 있어 로그에 남기지 않는다.
            log.warn("본문 파싱 실패 → 400 reason={}", e.getClass().getSimpleName());
            return ResponseEntity.badRequest().build();
        }
        String type = root == null ? null : root.path("type").asText(null);
        if (type == null) {
            log.warn("type 누락 → 400");
            return ResponseEntity.badRequest().build();
        }

        if ("url_verification".equals(type)) {
            String challenge = root.path("challenge").asText(null);
            if (challenge == null) {
                log.warn("challenge 누락 → 400");
                return ResponseEntity.badRequest().build();
            }
            return ResponseEntity.ok(Map.of("challenge", challenge));
        }

        if (!"event_callback".equals(type)) {
            // Slack이 보낼 수 있는 다른 콜백 타입(예: app_uninstalled 등)까지 400으로 거절하면 재전송만 늘린다.
            // 처리하지 않는 타입은 무해하게 무시하고 200으로 확인해 재전송을 막는다.
            log.info("처리 대상 아닌 type 무시 type={}", type);
            return ResponseEntity.ok().build();
        }

        return handleEventCallback(root, retryNum, servletRequest);
    }

    private ResponseEntity<?> handleEventCallback(JsonNode root, String retryNum, HttpServletRequest servletRequest) {
        SlackMessageEvent event;
        try {
            event = SlackMessageEvent.from(root);
        } catch (IllegalArgumentException e) {
            log.warn("이벤트 필드 부족 → 400 reason={}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }

        // AckLoggingFilter가 응답 쓰기 실패를 event_id와 함께 로그로 남길 수 있게 요청 속성에 심어둔다.
        servletRequest.setAttribute(AckLoggingFilter.EVENT_ID_ATTR, event.eventId());

        if (event.shouldIgnore()) {
            // bot_id·subtype 있는 이벤트를 거르지 않으면 무한 루프가 된다(AGENTS.md 함정). 큐에 넣지 않는다.
            log.info("무시된 이벤트 event_id={} bot_id={} subtype={}", event.eventId(), event.botId(), event.subtype());
            return ResponseEntity.ok().build();
        }

        // 중복 입력은 여기서 거르지 않는다(ARCHITECTURE §3.1) — 워커가 M11 선점 결과표로 억제한다.
        long receivedAtMs = receivedAtMs(servletRequest);
        PublishResult result = publisher.publish(event, receivedAtMs, retryNum);
        return switch (result) {
            case PublishResult.Enqueued enqueued -> {
                log.info("발행 완료 event_id={} stream_id={}", event.eventId(), enqueued.streamId());
                yield ResponseEntity.ok().build();
            }
            case PublishResult.Failed failed -> {
                log.error("발행 실패 event_id={} reason={}", event.eventId(), failed.reason());
                yield ResponseEntity.status(503).build();
            }
            case PublishResult.Unconfirmed unconfirmed -> {
                log.error("발행 확인 불가 event_id={} reason={}", event.eventId(), unconfirmed.reason());
                yield ResponseEntity.status(503).build();
            }
        };
    }

    private static long receivedAtMs(HttpServletRequest servletRequest) {
        Object attr = servletRequest.getAttribute(AckLoggingFilter.RECEIVED_AT_MS_ATTR);
        // 필터가 항상 먼저 실행되므로 정상 경로에서는 null이 아니다 — 없으면(테스트 등) 지금 시각으로 대체한다.
        return attr instanceof Long l ? l : System.currentTimeMillis();
    }
}
