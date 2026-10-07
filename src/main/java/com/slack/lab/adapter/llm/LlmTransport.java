package com.slack.lab.adapter.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
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
 * LLM 채팅 어댑터와 분류 어댑터가 공유하는 전송 계층(M33에서 {@link OpenAiCompatibleLlmClient}로부터 추출). 마감 취소 로직
 * (M1.5 A2, `docs/EXPERIMENT-LOG.md` §3)을 두 어댑터가 따로 복제하면 이미 한 번 잡은 종류의 버그(제출 성공 뒤 예약 실패 오분류,
 * 본문 수신 중 정체)가 되살아날 수 있어 한 곳에 둔다. 동작은 추출 전과 같다.
 */
class LlmTransport {

    private static final Logger log = LoggerFactory.getLogger(LlmTransport.class);

    private final String baseUrl;
    private final long connectTimeoutMs;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;
    private final ScheduledExecutorService cancelTimer;

    /** 마감 취소 타이머는 호출자가 만들어 넘긴다 — 호출자(어댑터)가 수명을 소유하고, 테스트가 종료시켜 예약 실패를 유도한다. */
    LlmTransport(String baseUrl, long connectTimeoutMs, ObjectMapper mapper, ScheduledExecutorService cancelTimer) {
        this.baseUrl = baseUrl;
        this.connectTimeoutMs = connectTimeoutMs;
        this.mapper = mapper;
        // 연결 제한은 클라이언트 단위라 호출별로 표현할 수 없다. 고정값 3초(PLAN §3 M4).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .build();
        this.cancelTimer = cancelTimer;
    }

    static ScheduledExecutorService newCancelTimer() {
        return Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "llm-deadline-timer");
            t.setDaemon(true);
            return t;
        });
    }

    /** 요청 성공/응답 유무와 실패 사유를 함께 담는다. {@code response}가 null이면 실패, {@code timedOut}이면 기한 초과다. */
    record HttpOutcome(HttpResponse<String> response, boolean timedOut, ErrorInfo failure, boolean retryable) {
        static HttpOutcome ok(HttpResponse<String> response) {
            return new HttpOutcome(response, false, null, false);
        }

        static HttpOutcome timedOut(String detail) {
            return new HttpOutcome(null, true, ErrorInfo.of(ErrorCode.LLM_TIMEOUT, detail), true);
        }

        /** 연결 자체가 안 됐거나 요청이 나가기 전에 실패한 경우만 재시도 가능으로 본다(M13, "오류 분류는 클라이언트 경계에서"). */
        static HttpOutcome failed(ErrorInfo failure, boolean retryable) {
            return new HttpOutcome(null, false, failure, retryable);
        }
    }

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
    HttpOutcome execute(HttpRequest request, long remainingMs) {
        CompletableFuture<HttpResponse<String>> future;
        try {
            future = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            return HttpOutcome.failed(ErrorInfo.of(ErrorCode.LLM_REQUEST_FAILED, "submit:" + e.getClass().getSimpleName()), true);
        }
        java.util.concurrent.ScheduledFuture<?> cancelTask;
        try {
            cancelTask = cancelTimer.schedule(() -> future.cancel(true), remainingMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            future.cancel(true);
            log.warn("LLM 취소 타이머 예약 실패(재시도 가능) reason={}", e.getClass().getSimpleName());
            return HttpOutcome.failed(ErrorInfo.of(ErrorCode.LLM_REQUEST_FAILED, "cancel_schedule:" + e.getClass().getSimpleName()), true);
        }
        try {
            HttpResponse<String> response = future.get(remainingMs + 500, TimeUnit.MILLISECONDS);
            return HttpOutcome.ok(response);
        } catch (CancellationException e) {
            return HttpOutcome.timedOut("cancelled_after_deadline");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof HttpTimeoutException) {
                return HttpOutcome.timedOut("request_timeout");
            }
            // 기한 타이머의 cancel(true)가 ExecutionException으로 감싸져 올 수 있다(경합, M14 중 간헐 실패로 발견).
            // 시간 초과로 분류하지 않으면 재시도 가능한 기한 초과가 영구 실패(즉시 안내)로 바뀐다.
            if (cause instanceof CancellationException) {
                return HttpOutcome.timedOut("cancelled_after_deadline");
            }
            if (cause instanceof java.net.ConnectException || cause instanceof java.net.UnknownHostException) {
                return HttpOutcome.failed(ErrorInfo.of(ErrorCode.LLM_CONNECT_FAILED, cause), true);
            }
            return HttpOutcome.failed(cause == null ? ErrorInfo.of(ErrorCode.LLM_REQUEST_FAILED)
                    : ErrorInfo.of(ErrorCode.LLM_REQUEST_FAILED, cause), false);
        } catch (TimeoutException e) {
            // cancel(true) 예약이 도달하지 못한 방어적 경로. 감시 시간 초과 시 직접 취소한다.
            future.cancel(true);
            return HttpOutcome.timedOut("watchdog_timeout");
        } catch (InterruptedException e) {
            // 인터럽트를 받아도 진행 중인 HTTP 요청이 남으면 헤더 이후 본문 정체가 그대로 지속된다 — 반드시 취소한다.
            future.cancel(true);
            Thread.currentThread().interrupt();
            return HttpOutcome.failed(ErrorInfo.of(ErrorCode.LLM_REQUEST_FAILED, "interrupted"), false);
        } catch (CompletionException e) {
            return HttpOutcome.failed(ErrorInfo.of(ErrorCode.LLM_REQUEST_FAILED, e), false);
        } finally {
            cancelTask.cancel(false);
        }
    }


    /** 기동 시 모델 존재를 fail-fast로 확인한다 (pitfall 8, PLAN §3 M4). 예외를 던져 기동을 막는다. */
    void verifyModelExists(String modelId) {
        long deadline = connectTimeoutMs + 2_000;
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/models"))
                .timeout(Duration.ofMillis(deadline)) // 보조 수단. 진짜 취소는 execute()의 cancel(true)가 한다
                .GET().build();
        HttpOutcome outcome = execute(request, deadline);

        if (outcome.timedOut()) {
            throw new IllegalStateException(
                    "모델 확인 호출이 " + deadline + "ms 안에 끝나지 않음(취소됨): llm.base-url=" + baseUrl);
        }
        if (outcome.response() == null) {
            throw new IllegalStateException(
                    "모델 확인 호출 실패: llm.base-url=" + baseUrl + " reason=" + outcome.failure().text());
        }
        if (outcome.response().statusCode() / 100 != 2) {
            throw new IllegalStateException("모델 목록 조회 실패 status=" + outcome.response().statusCode());
        }
        JsonNode data;
        try {
            data = mapper.readTree(outcome.response().body()).path("data");
        } catch (Exception e) {
            throw new IllegalStateException("모델 목록 응답 파싱 실패: llm.base-url=" + baseUrl, e);
        }
        boolean found = false;
        for (JsonNode m : data) {
            if (modelId.equals(m.path("id").asText())) {
                found = true;
                break;
            }
        }
        if (!found) {
            throw new IllegalStateException(
                    "model=" + modelId + " 이 llm.base-url=" + baseUrl + " 에 없음. `ollama list`로 확인하라");
        }
    }

}
