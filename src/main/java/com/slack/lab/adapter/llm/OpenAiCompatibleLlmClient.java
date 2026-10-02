package com.slack.lab.adapter.llm;

import com.slack.lab.core.port.LlmClient;
import com.slack.lab.core.model.LlmMessage;
import com.slack.lab.config.LlmProperties;
import com.slack.lab.core.model.LlmResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.List;
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
    // qwen2.5:7b는 한국어 지시만으로는 중국어를 섞는다(M1 실측, M19 후 재발: 12건 중 1건이 중국어로 시작). 영어 지시가 더 잘 듣고,
    // 허용 범위(영어는 에러·명령어·기술 용어에만)를 구체적으로 적어야 "한국어로만"이 기술 설명을 망치지 않는다. 그래도 새므로
    // 응답을 검사해 한 번 다시 묻는 방어(아래 containsForeignScript)를 함께 둔다 — 프롬프트는 확률을 낮출 뿐 보장이 아니다.
    private static final String SYSTEM_PROMPT =
            "You are an on-call assistant in a Korean engineering team's Slack. Reply in Korean (한국어) only. "
                    + "English is allowed ONLY for error messages, exception/class names, log lines, commands, code, "
                    + "config keys and standard technical terms (e.g. NullPointerException, HTTP 503, connection pool, OOM). "
                    + "NEVER write Chinese (Hanzi / 汉字 / 中文) or Japanese, not even a single character, and never switch "
                    + "language mid-answer. Keep the answer concise, within 500 characters.\n"
                    + "한국어로만 답하라. 영어는 에러 메시지·예외/클래스명·로그·명령어·코드·설정 키·표준 기술 용어에만 허용한다. "
                    + "중국어(한자)와 일본어는 한 글자도 쓰지 마라.";
    // 다시 물을 때 문맥 끝에 붙이는 짧은 재강조.
    private static final String LANGUAGE_REMINDER =
            "Reply in Korean only. Do not use any Chinese or Japanese characters. 한국어로만 답하라.";
    // 두 번째 시도에 최소로 필요한 남은 시간. 이보다 적으면 다시 묻지 않고 재시도 가능한 실패로 돌려보낸다.
    private static final long MIN_LANGUAGE_RETRY_MS = 3_000;
    // 한자(CJK 통합·확장 A)와 일본어 가나. 한글·영문·숫자·기호는 해당 없다.
    private static final java.util.regex.Pattern FOREIGN_SCRIPT =
            java.util.regex.Pattern.compile("[\\u3040-\\u30ff\\u3400-\\u4dbf\\u4e00-\\u9fff]");

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
    public LlmResult chat(List<LlmMessage> messages, long remainingMs) {
        long start = System.nanoTime();
        LlmResult first = attempt(messages, remainingMs, false);
        if (!(first instanceof LlmResult.Success ok) || !containsForeignScript(ok.text())) {
            return first;
        }
        // 한국어·영어만 허용한다. 중국어·일본어가 섞인 답은 Slack에 보내지 않고 한 번 다시 묻는다.
        long left = remainingMs - elapsedMs(start);
        if (left < MIN_LANGUAGE_RETRY_MS) {
            log.warn("LLM 응답 언어 위반, 남은 시간 부족으로 다시 묻지 못함 left_ms={}", left);
            return new LlmResult.Failed("language_violation", elapsedMs(start), true);
        }
        log.warn("LLM 응답에 한자·가나가 섞임 — 언어 재강조 후 한 번 다시 묻는다 left_ms={}", left);
        LlmResult second = attempt(messages, left, true);
        long total = elapsedMs(start);
        if (second instanceof LlmResult.Success ok2) {
            if (containsForeignScript(ok2.text())) {
                log.warn("재시도 응답도 언어 위반 — 실패(재시도 가능)로 처리 total_ms={}", total);
                return new LlmResult.Failed("language_violation", total, true);
            }
            return new LlmResult.Success(ok2.text(), total);
        }
        return second;
    }

    /** 한자·가나가 한 글자라도 있으면 true. */
    static boolean containsForeignScript(String text) {
        return text != null && FOREIGN_SCRIPT.matcher(text).find();
    }

    private LlmResult attempt(List<LlmMessage> messages, long remainingMs, boolean reinforceLanguage) {
        if (remainingMs <= 0) {
            return new LlmResult.Failed("남은 기한 없음", 0, false);
        }
        long start = System.nanoTime();
        HttpRequest request;
        try {
            request = buildRequest(messages, remainingMs, reinforceLanguage);
        } catch (Exception e) {
            // 요청 준비 단계에서 예외가 나면 호출자(SlackEventHandler)까지 전파시키지 않는다 — LlmClient 계약은
            // 예외 없이 Failed를 돌려주는 것이다(위 parseContentSafely와 동일한 원칙). 입력 직렬화 실패는
            // 같은 입력으로 재시도해도 그대로 실패하므로 영구 실패다.
            log.warn("LLM 요청 준비 실패 reason={}", e.getClass().getSimpleName());
            return new LlmResult.Failed("request_build_failed:" + e.getClass().getSimpleName(), elapsedMs(start),
                    false);
        }

        // M13 흡수 과제(codex WATCH, docs/EXPERIMENT-LOG.md §2.10 LOW): 요청 준비(buildRequest)에 걸린 시간을
        // remainingMs에서 빼지 않으면 실제 취소 예약이 원래 예산보다 늦게 잡혀 총 처리 기한(A16)을 넘길 수 있다.
        long buildElapsedMs = elapsedMs(start);
        long budgetMs = remainingMs - buildElapsedMs;
        if (budgetMs <= 0) {
            return new LlmResult.TimedOut(buildElapsedMs);
        }

        HttpOutcome outcome = execute(request, budgetMs);
        long elapsed = elapsedMs(start);

        if (outcome.timedOut()) {
            log.warn("LLM 호출 기한 초과 reason={} elapsed_ms={}", outcome.failureReason(), elapsed);
            return new LlmResult.TimedOut(elapsed);
        }
        if (outcome.response() == null) {
            log.warn("LLM 호출 실패 elapsed_ms={} reason={}", elapsed, outcome.failureReason());
            return new LlmResult.Failed(outcome.failureReason(), elapsed, isRetryableFailure(outcome.failureReason()));
        }
        if (outcome.response().statusCode() / 100 != 2) {
            int status = outcome.response().statusCode();
            log.warn("LLM 호출 실패 status={} elapsed_ms={}", status, elapsed);
            // 5xx는 서버 쪽 일시 오류일 수 있어 재시도 가능, 4xx는 같은 요청을 다시 보내도 그대로 실패한다.
            return new LlmResult.Failed("status=" + status, elapsed, status / 100 == 5);
        }
        return parseContentSafely(outcome.response().body(), elapsed);
    }

    /** 연결 자체가 안 된 경우만 재시도 가능으로 분류한다(M13, PLAN "오류 분류는 클라이언트 경계에서"). */
    private static boolean isRetryableFailure(String reason) {
        return reason != null && (reason.contains("ConnectException") || reason.contains("UnknownHostException")
                || reason.startsWith("send_submit_failed") || reason.startsWith("cancel_schedule_failed"));
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
     *
     * <p>M13 흡수 과제(codex WATCH, docs/EXPERIMENT-LOG.md §2.10 MEDIUM, codex critic REVISE MAJOR-2로 실제
     * 수정): {@code sendAsync} 제출과 취소 타이머 예약을 같은 try 블록에 두면, 제출이 이미 성공한 뒤 예약만
     * 실패해도 요청이 나갔을 수 있는데 실패로 오분류된다. 제출 자체가 실패하는 경우와 취소 타이머 예약이
     * 실패하는 경우를 각각 별도로 잡는다. 타이머 예약이 실패하면(예: cancelTimer가 종료됨) 이미 제출된
     * future를 응답을 기다리지 않고 즉시 취소한다 — 취소 타이머가 없어 무기한 대기할 위험이 있기 때문이다.
     * 두 경우 모두 LlmClient에는 Unknown이 없어 재시도 가능한 실패로 본다 — 응답을 못 받았다는 점은
     * 동일하고, LLM 호출은 Slack 발신과 달리 재시도해도 사용자에게 보이는 중복 부작용이 없다.
     */
    private HttpOutcome execute(HttpRequest request, long remainingMs) {
        CompletableFuture<HttpResponse<String>> future;
        try {
            future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return new HttpOutcome(null, false, "send_submit_failed:" + e.getClass().getSimpleName());
        }
        java.util.concurrent.ScheduledFuture<?> cancelTask;
        try {
            cancelTask = cancelTimer.schedule(() -> future.cancel(true), remainingMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("LLM 취소 타이머 예약 실패(재시도 가능) reason={}", e.getClass().getSimpleName());
            return new HttpOutcome(null, false, "cancel_schedule_failed:" + e.getClass().getSimpleName());
        }
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
            // 기한 타이머의 cancel(true)가 ExecutionException으로 감싸져 올 수 있다(경합, M14 중 간헐 실패로 발견).
            // 시간 초과로 분류하지 않으면 재시도 가능한 기한 초과가 영구 실패(즉시 안내)로 바뀐다.
            if (cause instanceof CancellationException) {
                return new HttpOutcome(null, true, "cancelled_after_deadline");
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
            return new LlmResult.Failed("parse_failed:" + e.getClass().getSimpleName(), elapsed, false);
        }
        if (!content.isTextual() || content.asText().isBlank()) {
            log.warn("LLM 응답에 유효한 content 없음 elapsed_ms={}", elapsed);
            return new LlmResult.Failed("empty_or_missing_content", elapsed, false);
        }
        log.info("LLM 호출 성공 elapsed_ms={}", elapsed);
        return new LlmResult.Success(content.asText(), elapsed);
    }

    private HttpRequest buildRequest(List<LlmMessage> messages, long remainingMs, boolean reinforceLanguage) {
        List<Map<String, String>> wire = new java.util.ArrayList<>();
        wire.add(Map.of("role", "system", "content", SYSTEM_PROMPT));
        for (LlmMessage m : messages) {
            wire.add(Map.of("role", m.role(), "content", m.content()));
        }
        if (reinforceLanguage) {
            wire.add(Map.of("role", "system", "content", LANGUAGE_REMINDER));
        }
        Map<String, Object> body = Map.of(
                "model", props.model(),
                "messages", wire,
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
