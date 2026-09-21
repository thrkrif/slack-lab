package com.slack.lab.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SlackEventController {

    private static final Logger log = LoggerFactory.getLogger(SlackEventController.class);

    private final SlackSignatureVerifier verifier;
    private final ObjectMapper mapper;

    public SlackEventController(SlackSignatureVerifier verifier, ObjectMapper mapper) {
        this.verifier = verifier;
        this.mapper = mapper;
    }

    @PostMapping("/slack/events")
    public ResponseEntity<?> receive(
            // 서명 계산에 raw 문자열이 필요하므로 객체 바인딩을 쓰지 않는다.
            @RequestBody String rawBody,
            @RequestHeader(value = "X-Slack-Request-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "X-Slack-Signature", required = false) String signature,
            @RequestHeader(value = "X-Slack-Retry-Num", required = false) String retryNum,
            @RequestHeader(value = "X-Slack-Retry-Reason", required = false) String retryReason) {

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

        // M3·M6에서 이 자리가 중복 억제 + 핸들러 호출로 바뀐다. 지금은 수신만 확인한다.
        // 2단계에서는 여기서 큐 저장 확인 후 ACK로 바뀐다.
        log.info("이벤트 수신(미처리) type={}", type);
        return ResponseEntity.ok().build();
    }
}
