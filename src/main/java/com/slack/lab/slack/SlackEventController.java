package com.slack.lab.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.event.AttemptHandle;
import com.slack.lab.event.ClaimResult;
import com.slack.lab.event.EventDeduplicator;
import com.slack.lab.event.HandlingResult;
import com.slack.lab.event.SlackEventHandler;
import com.slack.lab.event.SlackMessageEvent;
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
 * ARCHITECTURE §3.1 표대로 HTTP 응답을 결정한다. 처리 권한(claim)을 확보한 뒤에는 어떤 예외·처리 결과가 나와도
 * 200을 유지하고 로그만 남긴다 — 선점 전 내부 오류만 500이다.
 */
@RestController
public class SlackEventController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventController.class);

    private final SlackSignatureVerifier verifier;
    private final ObjectMapper mapper;
    private final EventDeduplicator deduplicator;
    private final SlackEventHandler handler;

    public SlackEventController(SlackSignatureVerifier verifier, ObjectMapper mapper,
            EventDeduplicator deduplicator, SlackEventHandler handler) {
        this.verifier = verifier;
        this.mapper = mapper;
        this.deduplicator = deduplicator;
        this.handler = handler;
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

        return handleEventCallback(root, servletRequest);
    }

    private ResponseEntity<?> handleEventCallback(JsonNode root, HttpServletRequest servletRequest) {
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
            // bot_id·subtype 있는 이벤트를 거르지 않으면 무한 루프가 된다(AGENTS.md 함정).
            log.info("무시된 이벤트 event_id={} bot_id={} subtype={}", event.eventId(), event.botId(), event.subtype());
            return ResponseEntity.ok().build();
        }

        ClaimResult claim = deduplicator.claim(event.eventId());
        if (claim instanceof ClaimResult.Duplicate duplicate) {
            log.info("중복 억제 event_id={} existing_state={}", event.eventId(), duplicate.existing());
            return ResponseEntity.ok().build();
        }

        AttemptHandle attempt = ((ClaimResult.Claimed) claim).handle();
        try {
            HandlingResult result = handler.handle(event, attempt);
            log.info("이벤트 처리 완료 event_id={} attempt_id={} result={}", event.eventId(), attempt.attemptId(),
                    result.getClass().getSimpleName());
        } catch (Exception e) {
            // 처리 권한 확보 후의 예외는 200을 유지한다 — 재전송으로 재시도되게 두지 않는다(중복 방지는 dedup이 담당).
            log.error("핸들러 처리 중 예외 event_id={} attempt_id={}", event.eventId(), attempt.attemptId(), e);
        }
        return ResponseEntity.ok().build();
    }
}
