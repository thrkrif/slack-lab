package com.slack.lab.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code conversations.replies}로 스레드를 읽는다(M14). 발신 결과가 불명인 건에서 "그 이벤트의 봇 답글이 실제로
 * 스레드에 있는가"를 확인하는 데 쓴다. 본문은 읽지도 남기지도 않는다 — 메타데이터만 본다.
 * 사람이 CLI로 부르는 조회라 예산 안에 넣지 않고 단순한 동기 호출을 쓴다.
 */
@Component
@ConditionalOnRole(AppRole.RECOVERY)
public class SlackThreadClient {

    private static final Logger log = LoggerFactory.getLogger(SlackThreadClient.class);
    private static final int PAGE_LIMIT = 200;
    // 한 스레드가 이보다 길면 다 읽지 못한 것으로 보고 "없음"을 단정하지 않는다.
    private static final int MAX_PAGES = 10;

    /** 일치한 답글 하나. */
    public record Match(String ts, String attemptId) {}

    /**
     * @param error 조회 실패 사유(성공이면 null)
     * @param complete 스레드 끝까지 읽었는지. false면 {@code matches}가 비어 있어도 "없음"이 아니다.
     */
    public record ScanResult(List<Match> matches, String error, boolean complete) {
        public boolean failed() {
            return error != null;
        }
    }

    private final SlackProperties props;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5)).build();

    public SlackThreadClient(SlackProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
    }

    public ScanResult findReplies(String channel, String threadTs, String eventId) {
        List<Match> matches = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode root;
            try {
                root = fetch(channel, threadTs, cursor);
            } catch (Exception e) {
                log.warn("스레드 조회 실패 reason={}", e.getClass().getSimpleName());
                return new ScanResult(matches, e.getClass().getSimpleName() + ": " + e.getMessage(), false);
            }
            if (!root.path("ok").asBoolean(false)) {
                String err = root.path("error").asText("unknown");
                log.warn("스레드 조회 실패(ok:false) error={}", err);
                return new ScanResult(matches, err, false);
            }
            if (!root.path("messages").isArray()) {
                // ok:true인데 messages가 없으면 응답을 신뢰할 수 없다 — "답글 없음"으로 읽으면 재처리를 부추긴다.
                return new ScanResult(matches, "invalid_response", false);
            }
            for (JsonNode msg : root.path("messages")) {
                JsonNode meta = msg.path("metadata");
                if (ReplyMetadata.EVENT_TYPE.equals(meta.path("event_type").asText())
                        && eventId.equals(meta.path("event_payload").path("event_id").asText())) {
                    matches.add(new Match(msg.path("ts").asText(), meta.path("event_payload").path("attempt_id").asText()));
                }
            }
            cursor = root.path("response_metadata").path("next_cursor").asText("");
            if (!root.path("has_more").asBoolean(false) && cursor.isBlank()) {
                return new ScanResult(matches, null, true);
            }
            if (cursor.isBlank()) {
                // 더 있다는데 다음 커서가 없다 — 끝까지 읽지 못했으므로 "없음"을 단정하지 않는다.
                return new ScanResult(matches, null, false);
            }
        }
        return new ScanResult(matches, null, false);
    }

    private JsonNode fetch(String channel, String threadTs, String cursor) throws Exception {
        StringBuilder url = new StringBuilder(props.baseUrl()).append("/conversations.replies?channel=")
                .append(enc(channel)).append("&ts=").append(enc(threadTs))
                .append("&include_all_metadata=true&limit=").append(PAGE_LIMIT);
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(enc(cursor));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("Authorization", "Bearer " + props.botToken())
                .timeout(Duration.ofSeconds(10)).GET().build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("status=" + response.statusCode());
        }
        return mapper.readTree(response.body());
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
