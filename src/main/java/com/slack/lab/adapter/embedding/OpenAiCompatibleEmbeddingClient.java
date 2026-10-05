package com.slack.lab.adapter.embedding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.RagProperties;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.port.EmbeddingClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OpenAI 호환 스키마({@code POST /embeddings}, {@code data[0].embedding})로만 통신한다. 벤더 교체는
 * {@code rag.embedding-base-url}로 한다(규칙 3과 같은 원칙). 전송 계층은 LLM 클라이언트와 같은 패턴이다: 호출별 남은 기한에
 * 예약한 {@code future.cancel(true)}가 취소의 권한이고 {@code request.timeout}은 보조다(EXPERIMENT-LOG §3).
 * 리다이렉트는 따라가지 않는다 — 허용 호스트 검사를 우회하는 경로를 만들지 않기 위해서다.
 */
public class OpenAiCompatibleEmbeddingClient implements EmbeddingClient, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleEmbeddingClient.class);

    private final RagProperties props;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService cancelTimer;

    public OpenAiCompatibleEmbeddingClient(RagProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(props.connectTimeoutMs()))
                .build();
        this.cancelTimer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "embedding-deadline-timer");
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public void close() {
        cancelTimer.shutdownNow();
        httpClient.shutdownNow(); // 진행 중인 요청·연결까지 닫는다
    }

    @Override
    public EmbeddingResult embed(String text, long remainingMs) {
        long start = System.nanoTime();
        if (remainingMs <= 0) {
            return new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_NO_BUDGET), 0, false);
        }
        HttpRequest request;
        try {
            request = buildRequest(text, remainingMs);
        } catch (Exception e) {
            return new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_RESPONSE_INVALID, "build:" + e.getClass().getSimpleName()),
                    elapsedMs(start), false);
        }
        long budgetMs = remainingMs - elapsedMs(start);
        if (budgetMs <= 0) {
            return new EmbeddingResult.TimedOut(elapsedMs(start));
        }

        Outcome outcome = execute(request, budgetMs);
        long elapsed = elapsedMs(start);
        if (outcome.timedOut) {
            log.warn("임베딩 호출 기한 초과 elapsed_ms={}", elapsed);
            return new EmbeddingResult.TimedOut(elapsed);
        }
        if (outcome.response == null) {
            log.warn("임베딩 호출 실패 elapsed_ms={} reason={}", elapsed, outcome.failure.text());
            return new EmbeddingResult.Failed(outcome.failure, elapsed, outcome.retryable);
        }
        int status = outcome.response.statusCode();
        if (status / 100 != 2) {
            log.warn("임베딩 호출 실패 status={} elapsed_ms={}", status, elapsed);
            // 5xx는 일시 오류일 수 있어 재시도 가능, 4xx(모델 없음 포함)는 같은 요청을 다시 보내도 그대로다.
            return new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_HTTP_ERROR, String.valueOf(status)), elapsed,
                    status / 100 == 5);
        }
        EmbeddingResult parsed = parse(outcome.response.body(), elapsed);
        long total = elapsedMs(start);
        // 파싱까지 포함해 기한을 다시 본다. 마감 뒤에 도착한 결과는 쓰지 않는다(경합으로 취소를 놓친 경우의 방어).
        if (total > remainingMs && parsed instanceof EmbeddingResult.Success) {
            log.warn("임베딩 결과가 기한 뒤에 도착해 버린다 elapsed_ms={} deadline_ms={}", total, remainingMs);
            return new EmbeddingResult.TimedOut(total);
        }
        return parsed;
    }

    private EmbeddingResult parse(String body, long elapsed) {
        JsonNode vector;
        try {
            vector = mapper.readTree(body).path("data").path(0).path("embedding");
        } catch (Exception e) {
            return new EmbeddingResult.Failed(
                    ErrorInfo.of(ErrorCode.EMBEDDING_RESPONSE_INVALID, "parse:" + e.getClass().getSimpleName()), elapsed, false);
        }
        if (!vector.isArray() || vector.isEmpty()) {
            return new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_RESPONSE_INVALID, "empty_embedding"), elapsed,
                    false);
        }
        if (vector.size() != props.embeddingDimension()) {
            // 모델을 바꿨는데 설정의 차원을 안 바꿨거나 그 반대다. 저장된 벡터와 비교할 수 없으므로 쓰지 않는다.
            return new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                    "got=" + vector.size() + " expected=" + props.embeddingDimension()), elapsed, false);
        }
        float[] out = new float[vector.size()];
        for (int i = 0; i < out.length; i++) {
            JsonNode n = vector.get(i);
            if (!n.isNumber() || !Float.isFinite((float) n.asDouble())) {
                return new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_RESPONSE_INVALID, "non_numeric_value"),
                        elapsed, false);
            }
            out[i] = (float) n.asDouble();
        }
        return new EmbeddingResult.Success(out, elapsed);
    }

    private HttpRequest buildRequest(String text, long remainingMs) throws Exception {
        String json = mapper.writeValueAsString(Map.of("model", props.embeddingModel(), "input", text));
        return HttpRequest.newBuilder(URI.create(props.embeddingBaseUrl() + "/embeddings"))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofMillis(remainingMs))
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
    }

    private record Outcome(HttpResponse<String> response, boolean timedOut, ErrorInfo failure, boolean retryable) {}

    private Outcome execute(HttpRequest request, long remainingMs) {
        CompletableFuture<HttpResponse<String>> future;
        try {
            future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return new Outcome(null, false,
                    ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED, "submit:" + e.getClass().getSimpleName()), true);
        }
        ScheduledFuture<?> cancelTask;
        try {
            cancelTask = cancelTimer.schedule(() -> future.cancel(true), remainingMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            return new Outcome(null, false,
                    ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED, "cancel_schedule:" + e.getClass().getSimpleName()), true);
        }
        try {
            return new Outcome(future.get(remainingMs + 500, TimeUnit.MILLISECONDS), false, null, false);
        } catch (CancellationException e) {
            return timedOut();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException || cause instanceof CancellationException) {
                return timedOut();
            }
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
                return new Outcome(null, false, ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED, cause), true);
            }
            return new Outcome(null, false, cause == null ? ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED)
                    : ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED, cause), false);
        } catch (TimeoutException e) {
            future.cancel(true);
            return timedOut();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            return new Outcome(null, false, ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED, "interrupted"), false);
        } finally {
            cancelTask.cancel(false);
        }
    }

    private static Outcome timedOut() {
        return new Outcome(null, true, ErrorInfo.of(ErrorCode.EMBEDDING_TIMEOUT), true);
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
