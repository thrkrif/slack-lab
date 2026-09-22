package com.slack.lab.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
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

/**
 * OpenAI 호환 스키마(`/chat/completions`, `choices[0].message.content`)로만 통신한다.
 * 벤더 교체는 {@code llm.base-url}로 한다 (AGENTS.md 규칙 3).
 *
 * 전송 계층은 M1.5 스파이크 결론(A2)을 따른다: {@code request.timeout}은 응답 헤더까지만 덮으므로
 * 보조로만 쓰고, 호출별 남은 기한에 예약한 {@code future.cancel(true)}를 취소의 권한으로 삼는다
 * (`docs/EXPERIMENT-LOG.md` §3). {@link #execute}가 이 패턴을 채팅 호출과 모델 확인 양쪽에서 공유한다 —
 * 모델 확인만 동기 호출로 따로 두면 본문 수신 중 정체에서 기동이 무기한 대기할 수 있다(codex 리뷰로 발견).
 */
public class OpenAiCompatibleLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleLlmClient.class);
    // qwen2.5:7b가 느슨한 지시에서는 중국어·영어를 섞어 답하는 경우가 있어(실측), 금지 조건을 명시적으로 반복한다.
    private static final String SYSTEM_PROMPT =
            "너는 한국어로만 답하는 챗봇이다. 어떤 경우에도 중국어·영어·다른 언어 단어를 섞지 마라. "
                    + "모든 문장을 한국어로만 작성하라. 간결하게 500자 이내로 답하라.";

    private final LlmProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService cancelTimer;

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        // 연결 제한은 클라이언트 단위라 호출별로 표현할 수 없다. 고정값 3초(PLAN §3 M4).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .build();
        this.cancelTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-deadline-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public LlmResult chat(String prompt, long remainingMs) {
        if (remainingMs <= 0) {
            return new LlmResult.Failed("남은 기한 없음", 0);
        }
        long start = System.nanoTime();
        HttpRequest request;
        try {
            request = buildRequest(prompt, remainingMs);
        } catch (Exception e) {
            // 요청 준비 단계에서 예외가 나면 호출자(SlackEventHandler)까지 전파시키지 않는다 — LlmClient 계약은
            // 예외 없이 Failed를 돌려주는 것이다(위 parseContentSafely와 동일한 원칙).
            log.warn("LLM 요청 준비 실패 reason={}", e.getClass().getSimpleName());
            return new LlmResult.Failed("request_build_failed:" + e.getClass().getSimpleName(), elapsedMs(start));
        }
        HttpOutcome outcome = execute(request, remainingMs);
        long elapsed = elapsedMs(start);

        if (outcome.timedOut()) {
            log.warn("LLM 호출 기한 초과 reason={} elapsed_ms={}", outcome.failureReason(), elapsed);
            return new LlmResult.TimedOut(elapsed);
        }
        if (outcome.response() == null) {
            log.warn("LLM 호출 실패 elapsed_ms={} reason={}", elapsed, outcome.failureReason());
            return new LlmResult.Failed(outcome.failureReason(), elapsed);
        }
        if (outcome.response().statusCode() / 100 != 2) {
            log.warn("LLM 호출 실패 status={} elapsed_ms={}", outcome.response().statusCode(), elapsed);
            return new LlmResult.Failed("status=" + outcome.response().statusCode(), elapsed);
        }
        return parseContentSafely(outcome.response().body(), elapsed);
    }

    /** 기동 시 모델 존재를 fail-fast로 확인한다 (pitfall 8, PLAN §3 M4). 예외를 던져 기동을 막는다. */
    void verifyModelExists() {
        long deadline = props.connectTimeoutMs() + 2_000;
        HttpRequest request = HttpRequest.newBuilder(URI.create(props.baseUrl() + "/models"))
                .timeout(Duration.ofMillis(deadline)) // 보조 수단. 진짜 취소는 execute()의 cancel(true)가 한다
                .GET().build();
        HttpOutcome outcome = execute(request, deadline);

        if (outcome.timedOut()) {
            throw new IllegalStateException(
                    "모델 확인 호출이 " + deadline + "ms 안에 끝나지 않음(취소됨): llm.base-url=" + props.baseUrl());
        }
        if (outcome.response() == null) {
            throw new IllegalStateException(
                    "모델 확인 호출 실패: llm.base-url=" + props.baseUrl() + " reason=" + outcome.failureReason());
        }
        if (outcome.response().statusCode() / 100 != 2) {
            throw new IllegalStateException("모델 목록 조회 실패 status=" + outcome.response().statusCode());
        }
        JsonNode data;
        try {
            data = mapper.readTree(outcome.response().body()).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("모델 목록 응답 파싱 실패: llm.base-url=" + props.baseUrl(), e);
        }
        boolean found = false;
        for (JsonNode m : data) {
            if (props.model().equals(m.path("id").asText())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException(
                    "llm.model=" + props.model() + " 이 llm.base-url=" + props.baseUrl() + " 에 없음. `ollama list`로 확인하라");
        }
    }

    /** 첫 호출의 모델 적재 지연을 기동 시점으로 옮긴다. 실패해도 기동을 막지 않는다(웜업은 최선 노력). */
    void warmUp() {
        long t0 = System.nanoTime();
        LlmResult result = chat("ping", Math.max(props.deadlineMs(), 10_000));
        log.info("LLM 웜업 결과={} elapsed_ms={}", result.getClass().getSimpleName(), elapsedMs(t0));
    }

    /** 요청 성공/응답 유무와 실패 사유를 함께 담는다. {@code response}가 null이면 실패, {@code timedOut}이면 기한 초과다. */
    private record HttpOutcome(HttpResponse<String> response, boolean timedOut, String failureReason) {}

    /**
     * sendAsync + 호출별 cancel(true) 패턴을 한 곳에 모은다(M1.5 A2). 예외를 던지지 않고 분류된 결과를 돌려준다 —
     * 호출자마다 반복해서 예외 처리를 흩어두면 놓치기 쉽다(codex 리뷰: 파싱 예외 누출, interrupt 시 미취소).
     */
    private HttpOutcome execute(HttpRequest request, long remainingMs) {
        CompletableFuture<HttpResponse<String>> future =
                httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var cancelTask = cancelTimer.schedule(() -> future.cancel(true), remainingMs, TimeUnit.MILLISECONDS);
        try {
            HttpResponse<String> response = future.get(remainingMs + 500, TimeUnit.MILLISECONDS);
            return new HttpOutcome(response, false, null);
        } catch (CancellationException e) {
            return new HttpOutcome(null, true, "cancelled_after_deadline");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                return new HttpOutcome(null, true, "request_timeout");
            }
            return new HttpOutcome(null, false, cause == null ? "unknown" : cause.getClass().getSimpleName());
        } catch (TimeoutException e) {
            // cancel(true) 예약이 도달하지 못한 방어적 경로. 감시 시간 초과 시 직접 취소한다.
            future.cancel(true);
            return new HttpOutcome(null, true, "watchdog_timeout");
        } catch (InterruptedException e) {
            // 인터럽트를 받아도 진행 중인 HTTP 요청이 남으면 헤더 이후 본문 정체가 그대로 지속된다 — 반드시 취소한다.
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new HttpOutcome(null, false, "interrupted");
        } catch (CompletionException e) {
            return new HttpOutcome(null, false, e.getClass().getSimpleName());
        } finally {
            cancelTask.cancel(false);
        }
    }

    /** LlmClient 계약(예외 없이 실패 반환)을 지킨다 — 파싱 오류·빈 응답을 모두 Failed로 돌린다. */
    private LlmResult parseContentSafely(String body, long elapsed) {
        JsonNode content;
        try {
            content = mapper.readTree(body).path("choices").path(0).path("message").path("content");
        } catch (Exception e) {
            log.warn("LLM 응답 파싱 실패 elapsed_ms={} reason={}", elapsed, e.getClass().getSimpleName());
            return new LlmResult.Failed("parse_failed:" + e.getClass().getSimpleName(), elapsed);
        }
        if (!content.isTextual() || content.asText().isBlank()) {
            log.warn("LLM 응답에 유효한 content 없음 elapsed_ms={}", elapsed);
            return new LlmResult.Failed("empty_or_missing_content", elapsed);
        }
        log.info("LLM 호출 성공 elapsed_ms={}", elapsed);
        return new LlmResult.Success(content.asText(), elapsed);
    }

    private HttpRequest buildRequest(String prompt, long remainingMs) {
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "messages", java.util.List.of(
                        Map.of("role", "system", "content", SYSTEM_PROMPT),
                        Map.of("role", "user", "content", prompt)),
                "max_tokens", props.maxTokens(),
                "keep_alive", props.keepAlive(),
                // 언어 혼용(중국어·영어 섞임) 실측 후 낮춤 — 창의성보다 지시 준수가 우선이다.
                "temperature", 0.3);
        String json;
        try {
            json = mapper.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException("LLM 요청 직렬화 실패", e);
        }
        return HttpRequest.newBuilder(URI.create(props.baseUrl() + "/chat/completions"))
                .header("Content-Type", "application/json")
                // 보조 수단: 헤더 수신 전 정체는 여기서도 걸리지만, 헤더 수신 후 본문 정체는 못 끊는다(M1.5 결론).
                // 진짜 취소는 execute()가 예약한 cancel(true)가 한다.
                .timeout(Duration.ofMillis(remainingMs))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
