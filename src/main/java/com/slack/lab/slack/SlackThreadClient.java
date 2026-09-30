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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Slack 스레드 조회 클라이언트. 두 곳에서 쓴다: (1) recovery CLI의 {@code check}가 발신 결과 불명 건의 답글을
 * 메타데이터로 찾고(사람이 부르는 조회라 느려도 된다), (2) 워커가 스레드 문맥을 읽는다(M16 — LLM 50초 예산 안의
 * 3초 기한이 걸려 있다). 기한은 응답 본문 수신까지 적용한다: JDK 요청 timeout은 헤더까지만 막으므로 비동기 전송
 * 뒤 {@code future.get(기한)}과 {@code cancel}로 끊는다. 본문은 로그에 남기지 않는다.
 */
@Component
@ConditionalOnRole({AppRole.RECOVERY, AppRole.WORKER, AppRole.ALL})
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

    /** 스레드 메시지 하나. 문맥 조립에 필요한 필드만 담는다. */
    public record ThreadMessage(String ts, String user, String botId, String text) {}

    /** {@code complete}가 false면 끝까지 읽지 못한 것이다. */
    public record FetchResult(List<ThreadMessage> messages, String error, boolean complete) {
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

    /**
     * 스레드 메시지를 시간순으로 읽는다(M16). {@code deadlineMs} 안에 끝내지 못하면 실패로 돌려준다 — 뒤쪽 페이지가
     * 빠진 채 "문맥"으로 쓰면 가장 최근 대화가 없는 문맥이 된다.
     */
    public FetchResult fetchMessages(String channel, String threadTs, long deadlineMs) {
        long deadline = System.nanoTime() + deadlineMs * 1_000_000L;
        List<ThreadMessage> out = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            long remaining = (deadline - System.nanoTime()) / 1_000_000L;
            if (remaining <= 0) {
                return new FetchResult(out, "deadline_exceeded", false);
            }
            JsonNode root;
            try {
                root = fetch(channel, threadTs, cursor, remaining);
            } catch (Exception e) {
                return new FetchResult(out, e.getClass().getSimpleName(), false);
            }
            if (!root.path("ok").asBoolean(false)) {
                return new FetchResult(out, root.path("error").asText("unknown"), false);
            }
            if (!root.path("messages").isArray()) {
                return new FetchResult(out, "invalid_response", false);
            }
            for (JsonNode m : root.path("messages")) {
                out.add(new ThreadMessage(m.path("ts").asText(""), m.path("user").asText(""),
                        m.path("bot_id").asText(""), m.path("text").asText("")));
            }
            cursor = root.path("response_metadata").path("next_cursor").asText("");
            if (!root.path("has_more").asBoolean(false) && cursor.isBlank()) {
                return new FetchResult(out, null, true);
            }
            if (cursor.isBlank()) {
                return new FetchResult(out, "missing_cursor", false);
            }
        }
        return new FetchResult(out, "too_many_pages", false);
    }

    private JsonNode fetch(String channel, String threadTs, String cursor) throws Exception {
        return fetch(channel, threadTs, cursor, 10_000);
    }

    private JsonNode fetch(String channel, String threadTs, String cursor, long timeoutMs) throws Exception {
        StringBuilder url = new StringBuilder(props.baseUrl()).append("/conversations.replies?channel=")
                .append(enc(channel)).append("&ts=").append(enc(threadTs))
                .append("&include_all_metadata=true&limit=").append(PAGE_LIMIT);
        if (cursor != null && !cursor.isBlank()) {
            url.append("&cursor=").append(enc(cursor));
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .header("Authorization", "Bearer " + props.botToken())
                .timeout(Duration.ofMillis(Math.max(1, timeoutMs))).GET().build();
        return mapper.readTree(send(request, timeoutMs));
    }

    /** 응답 본문까지 {@code timeoutMs} 안에 받는다. 넘기면 요청을 취소하고 예외를 던진다. */
    private String send(HttpRequest request, long timeoutMs) throws Exception {
        CompletableFuture<HttpResponse<String>> future = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        HttpResponse<String> response;
        try {
            response = future.get(Math.max(1, timeoutMs), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("deadline_exceeded");
        } catch (ExecutionException e) {
            future.cancel(true);
            throw e.getCause() instanceof Exception cause ? cause : e;
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        }
        if (response.statusCode() / 100 != 2) {
            throw new IllegalStateException("status=" + response.statusCode());
        }
        return response.body();
    }

    /** 이 봇의 식별자(M16). 스레드에서 "내가 한 말"과 다른 봇의 말을 가르는 데 쓴다. */
    public record Identity(String userId, String botId) {}

    private volatile Identity identity;

    /**
     * {@code auth.test}로 자기 식별자를 알아 캐시한다. 실패하면 null이고 다음 호출에서 다시 시도한다 — 호출자는
     * 식별 없이도 동작해야 한다.
     */
    public Identity identity(long timeoutMs) {
        Identity cached = identity;
        if (cached != null) {
            return cached;
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/auth.test"))
                    .header("Authorization", "Bearer " + props.botToken())
                    .timeout(Duration.ofMillis(Math.max(1, timeoutMs)))
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            JsonNode root = mapper.readTree(send(request, timeoutMs));
            if (!root.path("ok").asBoolean(false)) {
                return null;
            }
            Identity found = new Identity(root.path("user_id").asText(""), root.path("bot_id").asText(""));
            if (found.userId().isBlank() && found.botId().isBlank()) {
                return null;
            }
            identity = found;
            return found;
        } catch (Exception e) {
            log.warn("auth.test 실패 — 봇 식별 없이 진행 reason={}", e.getClass().getSimpleName());
            return null;
        }
    }

    private static String enc(String v) {
        return URLEncoder.encode(v, StandardCharsets.UTF_8);
    }
}
