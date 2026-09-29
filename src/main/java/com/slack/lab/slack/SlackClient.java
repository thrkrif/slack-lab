package com.slack.lab.slack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@code chat.postMessage} 발신. Slack API는 실패해도 HTTP 200을 주므로 본문 {@code ok}를 본다(함정 3).
 * 전송 계층은 M1.5 결론(A2)과 동일하게 {@code sendAsync} + 호출별 {@code cancel(true)}를 쓴다.
 *
 * codex 리뷰로 발견: {@code internal_error}·{@code fatal_error} 등은 Slack 문서상 일부 처리가 실제로는
 * 성공했을 가능성이 있다 — 이런 응답과 5xx, 불완전한(ok 누락·비boolean·ts 없는) 응답을 명확한 실패로 기록하면
 * M6이 재시도 가능한 실패로 오분류해 중복 발신 위험이 생긴다. 모두 결과 불명으로 남긴다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.REACTOR, AppRole.RECOVERY, AppRole.ALL})
public class SlackClient {

    private static final Logger log = LoggerFactory.getLogger(SlackClient.class);

    // Slack 공식 문서(chat.postMessage 오류 목록)가 부분 처리 가능성을 명시하는 오류 코드.
    private static final Set<String> AMBIGUOUS_ERRORS = Set.of("internal_error", "fatal_error", "service_unavailable");

    private final SlackProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService cancelTimer;

    public SlackClient(SlackProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(Math.min(3_000, props.sendDeadlineMs())))
                .build();
        this.cancelTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "slack-deadline-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * @param remainingMs 이 호출 시작 시점부터 허용되는 남은 시간(절대 기한이 아니다). {@code min(전송
     *                     시작+slack.send-deadline-ms, t0+processing.total-deadline-ms)}로 호출자(M6)가
     *                     계산해 넘긴다. 예산이 없으면 발신을 시작하지 않는다(A15).
     */
    public SlackSendResult postMessage(String channel, String threadTs, String text, long remainingMs) {
        if (remainingMs <= 0) {
            log.warn("Slack 발신 예산 없음 — 발신 시작 안 함");
            return new SlackSendResult.Failed("budget_exhausted");
        }
        long start = System.nanoTime();

        HttpRequest request;
        try {
            request = buildRequest(channel, threadTs, text, remainingMs);
        } catch (Exception e) {
            // 요청을 만들다 실패했으면 네트워크로 나가지 않았으므로 명확한 실패다. 같은 입력으로 다시
            // 시도해도 그대로 실패하므로(M13) 영구 실패다.
            log.warn("Slack 요청 준비 실패 elapsed_ms={} reason={}", elapsedMs(start), e.getClass().getSimpleName());
            return new SlackSendResult.Failed("request_build_failed:" + e.getClass().getSimpleName());
        }

        // M13 흡수 과제(codex WATCH, docs/EXPERIMENT-LOG.md §2.11 LOW): buildRequest에 걸린 시간을 예산에서
        // 빼지 않으면 취소 예약이 원래 남은 시간보다 늦게 잡혀 총 처리 기한(A16)을 넘길 수 있다.
        long buildElapsedMs = elapsedMs(start);
        long budgetMs = remainingMs - buildElapsedMs;
        if (budgetMs <= 0) {
            log.warn("Slack 요청 준비에 예산을 모두 써서 발신하지 않음 elapsed_ms={}", buildElapsedMs);
            return new SlackSendResult.Unknown("budget_exhausted_after_build");
        }

        CompletableFuture<HttpResponse<String>> future;
        try {
            future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            // sendAsync 제출 자체가 실패하면(예: 잘못된 URI) 이때도 아직 네트워크로 나가지 않았다.
            log.warn("Slack 발신 제출 실패 elapsed_ms={} reason={}", elapsedMs(start), e.getClass().getSimpleName());
            return new SlackSendResult.Failed("send_submit_failed:" + e.getClass().getSimpleName());
        }

        // M13 흡수 과제(codex WATCH, §2.10 MEDIUM): 제출(sendAsync)과 취소 타이머 예약을 같은 try에 두면,
        // 제출은 이미 성공한 뒤 예약만 실패해도 이미 나갔을 수 있는 요청을 Failed(미전송 확정)로 오분류한다.
        // 여기부터는 요청이 네트워크로 나갔을 수 있으므로 실패해도 결과 불명이다.
        java.util.concurrent.ScheduledFuture<?> cancelTask;
        try {
            cancelTask = cancelTimer.schedule(() -> future.cancel(true), budgetMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("Slack 발신 취소 타이머 예약 실패(결과 불명) elapsed_ms={} reason={}", elapsedMs(start),
                    e.getClass().getSimpleName());
            return new SlackSendResult.Unknown("cancel_schedule_failed:" + e.getClass().getSimpleName());
        }

        try {
            HttpResponse<String> response = future.get(budgetMs + 500, TimeUnit.MILLISECONDS);
            long elapsed = elapsedMs(start);
            if (response.statusCode() == 429) {
                // Rate limit — 미전송이 확실하고(Slack이 아예 처리하지 않고 거절), Retry-After가 있으면
                // 재시도 정책이 그 값을 기본 대기보다 우선해 쓴다(M13, PLAN "429 → Retry-After 반영").
                long retryAfterMs = parseRetryAfterMs(response);
                log.warn("Slack 발신 rate limit elapsed_ms={} retry_after_ms={}", elapsed, retryAfterMs);
                return new SlackSendResult.Failed("rate_limited", true, retryAfterMs);
            }
            if (response.statusCode() / 100 == 5) {
                // 5xx는 Slack 쪽에서 일부 처리됐을 수 있어 명확한 실패로 단정하지 않는다.
                log.warn("Slack 발신 서버 오류(결과 불명) status={} elapsed_ms={}", response.statusCode(), elapsed);
                return new SlackSendResult.Unknown("status=" + response.statusCode());
            }
            if (response.statusCode() / 100 != 2) {
                // 4xx(인증·권한·채널 오류 등)는 같은 요청을 다시 보내도 그대로 실패한다 — 영구 실패.
                log.warn("Slack 발신 실패 status={} elapsed_ms={}", response.statusCode(), elapsed);
                return new SlackSendResult.Failed("status=" + response.statusCode());
            }
            return parseBody(response.body(), elapsed);
        } catch (CancellationException e) {
            // 헤더 수신 전 취소면 미전송이 거의 확실하지만, 요청이 이미 네트워크로 나갔을 수도 있어 안전한 쪽(결과 불명)으로 분류한다.
            long elapsed = elapsedMs(start);
            log.warn("Slack 발신 기한 초과로 취소 elapsed_ms={}", elapsed);
            return new SlackSendResult.Unknown("cancelled_after_deadline");
        } catch (ExecutionException e) {
            long elapsed = elapsedMs(start);
            Throwable cause = e.getCause();
            if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
                // 연결조차 되지 않았으므로 전송 안 됐음이 확실하다.
                log.warn("Slack 발신 연결 실패 elapsed_ms={} reason={}", elapsed, cause.getClass().getSimpleName());
                // 연결 자체가 안 됐으므로 미전송이 확실하고, 다음 시도에서 연결이 회복될 수 있어 재시도 가능하다.
                return new SlackSendResult.Failed("connect_failed:" + cause.getClass().getSimpleName(), true, 0);
            }
            if (cause instanceof HttpTimeoutException) {
                log.warn("Slack 발신 읽기 시간 초과 elapsed_ms={}", elapsed);
                return new SlackSendResult.Unknown("read_timeout");
            }
            // 연결 이후 끊김(RST 등)은 요청이 도달했을 수 있어 결과 불명으로 남긴다.
            log.warn("Slack 발신 중 연결 끊김(결과 불명) elapsed_ms={} reason={}", elapsed,
                    cause == null ? "unknown" : cause.getClass().getSimpleName());
            return new SlackSendResult.Unknown(cause == null ? "unknown" : cause.getClass().getSimpleName());
        } catch (TimeoutException e) {
            future.cancel(true);
            long elapsed = elapsedMs(start);
            log.warn("Slack 발신 감시 시간 초과로 강제 취소 elapsed_ms={}", elapsed);
            return new SlackSendResult.Unknown("watchdog_timeout");
        } catch (InterruptedException e) {
            // 인터럽트를 받아도 진행 중인 요청을 취소하지 않으면 헤더 이후 본문 정체가 그대로 남는다(M4 codex 리뷰와 동일 결함).
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new SlackSendResult.Unknown("interrupted");
        } catch (CompletionException e) {
            return new SlackSendResult.Unknown(e.getClass().getSimpleName());
        } finally {
            cancelTask.cancel(false);
        }
    }

    private SlackSendResult parseBody(String body, long elapsed) {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            log.warn("Slack 응답 파싱 실패(결과 불명) elapsed_ms={}", elapsed);
            return new SlackSendResult.Unknown("parse_failed");
        }
        JsonNode ok = root.path("ok");
        if (!ok.isBoolean()) {
            // ok 필드가 없거나 boolean이 아니면(예: "true" 문자열) 응답을 신뢰할 수 없다.
            log.warn("Slack 응답에 유효한 ok 필드 없음(결과 불명) elapsed_ms={}", elapsed);
            return new SlackSendResult.Unknown("invalid_ok_field");
        }
        if (ok.asBoolean()) {
            JsonNode ts = root.path("ts");
            if (!ts.isTextual() || ts.asText().isBlank()) {
                // 성공이라면서 메시지 식별자가 없으면 완전한 성공으로 확정할 수 없다.
                log.warn("Slack 성공 응답에 ts 없음(결과 불명) elapsed_ms={}", elapsed);
                return new SlackSendResult.Unknown("success_without_ts");
            }
            log.info("Slack 발신 성공 elapsed_ms={}", elapsed);
            return new SlackSendResult.Success(ts.asText());
        }
        String error = root.path("error").asText("unknown");
        if (AMBIGUOUS_ERRORS.contains(error)) {
            log.warn("Slack 발신 결과 불명(부분 처리 가능) error={} elapsed_ms={}", error, elapsed);
            return new SlackSendResult.Unknown(error);
        }
        log.warn("Slack 발신 실패(ok:false) error={} elapsed_ms={}", error, elapsed);
        return new SlackSendResult.Failed(error);
    }

    private HttpRequest buildRequest(String channel, String threadTs, String text, long remainingMs) {
        Map<String, Object> body = threadTs == null
                ? Map.of("channel", channel, "text", text)
                : Map.of("channel", channel, "thread_ts", threadTs, "text", text);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("Slack 요청 직렬화 실패", e);
        }
        return HttpRequest.newBuilder(URI.create(props.baseUrl() + "/chat.postMessage"))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + props.botToken())
                .timeout(Duration.ofMillis(remainingMs)) // 보조 수단. 진짜 취소는 cancel(true)가 한다(M1.5 결론)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    /** {@code Retry-After}는 초 단위 정수다(Slack 문서). 없거나 파싱할 수 없으면 0 — 호출자가 기본 대기로 대체한다. */
    private static long parseRetryAfterMs(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000;
            } catch (NumberFormatException e) {
                return 0L;
            }
        }).orElse(0L);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
