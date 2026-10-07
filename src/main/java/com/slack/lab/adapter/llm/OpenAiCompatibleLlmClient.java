package com.slack.lab.adapter.llm;

import com.slack.lab.core.port.LlmClient;
import com.slack.lab.core.model.LlmMessage;
import com.slack.lab.config.LlmProperties;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.LlmResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
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
    private final LlmTransport transport;
    private final ObjectMapper mapper;
    // 필드로 유지하는 이유: 기존 단위 테스트가 이 타이머를 종료시켜 "예약 실패" 경로를 유도한다(전송부 추출 뒤에도 무변경).
    private final ScheduledExecutorService cancelTimer;

    public OpenAiCompatibleLlmClient(LlmProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.cancelTimer = LlmTransport.newCancelTimer();
        this.transport = new LlmTransport(props.baseUrl(), props.connectTimeoutMs(), mapper, cancelTimer);
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
            return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_LANGUAGE_VIOLATION), elapsedMs(start), true);
        }
        log.warn("LLM 응답에 한자·가나가 섞임 — 언어 재강조 후 한 번 다시 묻는다 left_ms={}", left);
        LlmResult second = attempt(messages, left, true);
        long total = elapsedMs(start);
        if (second instanceof LlmResult.Success ok2) {
            if (containsForeignScript(ok2.text())) {
                log.warn("재시도 응답도 언어 위반 — 실패(재시도 가능)로 처리 total_ms={}", total);
                return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_LANGUAGE_VIOLATION), total, true);
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
            return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_NO_BUDGET), 0, false);
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
            return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_REQUEST_BUILD_FAILED, e), elapsedMs(start), false);
        }

        // M13 흡수 과제(codex WATCH, docs/EXPERIMENT-LOG.md §2.10 LOW): 요청 준비(buildRequest)에 걸린 시간을
        // remainingMs에서 빼지 않으면 실제 취소 예약이 원래 예산보다 늦게 잡혀 총 처리 기한(A16)을 넘길 수 있다.
        long buildElapsedMs = elapsedMs(start);
        long budgetMs = remainingMs - buildElapsedMs;
        if (budgetMs <= 0) {
            return new LlmResult.TimedOut(buildElapsedMs);
        }

        LlmTransport.HttpOutcome outcome = transport.execute(request, budgetMs);
        long elapsed = elapsedMs(start);

        if (outcome.timedOut()) {
            log.warn("LLM 호출 기한 초과 reason={} elapsed_ms={}", outcome.failure().text(), elapsed);
            return new LlmResult.TimedOut(elapsed);
        }
        if (outcome.response() == null) {
            log.warn("LLM 호출 실패 elapsed_ms={} reason={}", elapsed, outcome.failure().text());
            return new LlmResult.Failed(outcome.failure(), elapsed, outcome.retryable());
        }
        if (outcome.response().statusCode() / 100 != 2) {
            int status = outcome.response().statusCode();
            log.warn("LLM 호출 실패 status={} elapsed_ms={}", status, elapsed);
            // 5xx는 서버 쪽 일시 오류일 수 있어 재시도 가능, 4xx는 같은 요청을 다시 보내도 그대로 실패한다.
            return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_HTTP_ERROR, String.valueOf(status)), elapsed, status / 100 == 5);
        }
        return parseContentSafely(outcome.response().body(), elapsed);
    }

    /** 기동 시 모델 존재를 fail-fast로 확인한다 (pitfall 8, PLAN §3 M4). 예외를 던져 기동을 막는다. */
    void verifyModelExists() {
        transport.verifyModelExists(props.model());
    }

    /** 첫 호출의 모델 적재 지연을 기동 시점으로 옮긴다. 실패해도 기동을 막지 않는다(웜업은 최선 노력). */
    void warmUp() {
        long t0 = System.nanoTime();
        LlmResult result = chat("ping", Math.max(props.deadlineMs(), 10_000));
        log.info("LLM 웜업 결과={} elapsed_ms={}", result.getClass().getSimpleName(), elapsedMs(t0));
    }

    /** LlmClient 계약(예외 없이 실패 반환)을 지킨다 — 파싱 오류·빈 응답을 모두 Failed로 돌린다. */
    private LlmResult parseContentSafely(String body, long elapsed) {
        JsonNode content;
        try {
            content = mapper.readTree(body).path("choices").path(0).path("message").path("content");
        } catch (Exception e) {
            log.warn("LLM 응답 파싱 실패 elapsed_ms={} reason={}", elapsed, e.getClass().getSimpleName());
            return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_RESPONSE_INVALID, "parse:" + e.getClass().getSimpleName()), elapsed, false);
        }
        if (!content.isTextual() || content.asText().isBlank()) {
            log.warn("LLM 응답에 유효한 content 없음 elapsed_ms={}", elapsed);
            return new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_RESPONSE_INVALID, "empty_content"), elapsed, false);
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
