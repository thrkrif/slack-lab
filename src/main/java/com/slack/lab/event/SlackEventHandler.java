package com.slack.lab.event;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.ExperimentProperties;
import com.slack.lab.config.ProcessingProperties;
import com.slack.lab.llm.LlmClient;
import com.slack.lab.llm.LlmMessage;
import com.slack.lab.llm.LlmProperties;
import com.slack.lab.llm.LlmResult;
import com.slack.lab.slack.ReplyMetadata;
import com.slack.lab.slack.SlackClient;
import com.slack.lab.slack.SlackProperties;
import com.slack.lab.slack.SlackSendResult;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP를 모른다(AGENTS.md 규칙 2, A13 — 이 패키지에 서블릿·스프링 HTTP 타입 import 금지). 큐 ACK도 모른다.
 * 입력은 이벤트와 {@link AttemptHandle}, 출력은 {@link HandlingResult}뿐이다. 종료 상태 기록·예외 가드는
 * M12부터 워커의 몫이다 — 핸들러는 {@code markSending} 게이트만 쥐고 있으면 된다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
public class SlackEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SlackEventHandler.class);
    private static final String FAILURE_NOTICE = "죄송해요, 지금 답변을 만들지 못했어요. 잠시 후 다시 멘션해 주세요.";
    private static final long NANOS_PER_MS = 1_000_000L;

    private final LlmClient llmClient;
    private final SlackClient slackClient;
    private final LlmProperties llmProps;
    private final SlackProperties slackProps;
    private final ProcessingProperties processingProps;
    private final ExperimentProperties experimentProps;
    private final ThreadContextSource threadContext;
    // 테스트가 프로세스를 죽이지 않고 halt 지점 도달만 확인하려고 바꿔 끼운다. 운영 경로는 항상 halt다.
    private Runnable halter = () -> Runtime.getRuntime().halt(137);

    public SlackEventHandler(LlmClient llmClient, SlackClient slackClient, LlmProperties llmProps,
            SlackProperties slackProps, ProcessingProperties processingProps, ExperimentProperties experimentProps,
            ThreadContextSource threadContext) {
        this.llmClient = llmClient;
        this.slackClient = slackClient;
        this.llmProps = llmProps;
        this.slackProps = slackProps;
        this.processingProps = processingProps;
        this.experimentProps = experimentProps;
        this.threadContext = threadContext;
    }

    void setHalter(Runnable halter) {
        this.halter = halter;
    }

    /**
     * 예외를 던질 수 있다 — 워커가 {@code attempt}의 {@code markSending} 여부로 Failed/Unknown을 가른다(M12).
     *
     * @param finalAttempt 이번이 마지막 시도인지(M13, {@code retries >= retry.max-retries || manual_run} —
     *     워커가 계산해 넘긴다). 재시도 가능한 오류라도 마지막 시도면 재시도 없이 최종 안내로 간다.
     */
    public HandlingResult handle(SlackMessageEvent event, AttemptHandle attempt, boolean finalAttempt) {
        long t0 = attempt.startNanos();
        // 인위적 지연과 LLM 호출 모두 같은 예산식을 쓴다 — 따로 계산하면 slow-mode가 총 처리 기한(A16)을
        // 넘기는 경로가 생긴다(code-reviewer MEDIUM: sleep에만 clamp가 빠져 있었음).
        long slowModeMs = experimentProps.slowModeMs();
        if (slowModeMs > 0) {
            long sleepMs = Math.min(slowModeMs, Math.max(llmBudgetMs(t0), 0));
            if (sleepMs > 0) {
                sleep(sleepMs);
            }
        }

        // 스레드 안 멘션이면 이전 대화를 문맥으로 붙인다(M16). 조회 시간도 LLM 단계 예산에 포함되므로, 조회 뒤에 남은
        // 예산을 다시 계산한다. 실패하면 문맥 없이 진행한다(구현체가 빈 목록을 돌려준다).
        List<LlmMessage> messages = new ArrayList<>();
        long contextBudgetMs = llmBudgetMs(t0);
        if (event.threadTs() != null && contextBudgetMs > 0) {
            messages.addAll(threadContext.fetch(event, contextBudgetMs));
        }
        messages.add(LlmMessage.user(event.promptText()));

        long llmRemainingMs = llmBudgetMs(t0);
        long llmStart = System.nanoTime();
        LlmResult llmResult = llmRemainingMs > 0
                ? llmClient.chat(messages, llmRemainingMs)
                : new LlmResult.TimedOut(0);
        attempt.recordPhase("llm_ms", elapsedMs(llmStart));

        String text;
        String kind;
        if (llmResult instanceof LlmResult.Success success) {
            kind = "answer";
            text = success.text();
            log.info("LLM 성공 event_id={} attempt_id={} elapsed_ms={}", event.eventId(), attempt.attemptId(),
                    success.elapsedMs());
        } else {
            // 재시도 가능한 오류(연결 실패·5xx·기한 초과)이고 마지막 시도가 아니면, 안내 없이 재시도만
            // 예약한다(M13 전이표 "LLM 재시도 가능, 마지막 시도 전"). 영구 오류이거나 마지막 시도면
            // 재시도하지 않고 바로 최종 안내로 간다 — 안내는 재시도 사슬을 만들지 않는다.
            boolean retryable = llmResult instanceof LlmResult.TimedOut
                    || ((LlmResult.Failed) llmResult).retryable();
            String llmStage = llmResult instanceof LlmResult.TimedOut timedOut
                    ? "llm_timeout(elapsed_ms=" + timedOut.elapsedMs() + ")"
                    : "llm_failed:" + ((LlmResult.Failed) llmResult).reason();
            if (retryable && !finalAttempt) {
                log.warn("LLM 재시도 가능한 실패 → 재시도 예약 event_id={} attempt_id={} stage={}", event.eventId(),
                        attempt.attemptId(), llmStage);
                return new HandlingResult.RetryRequested(llmStage, 0);
            }
            kind = "failure_notice";
            text = FAILURE_NOTICE;
            log.warn("LLM 실패 → 실패 안내로 전환 event_id={} attempt_id={} stage={} final_attempt={}", event.eventId(),
                    attempt.attemptId(), llmStage, finalAttempt);
        }

        HandlingResult result = send(event, attempt, t0, text, kind, finalAttempt);
        log.info("처리 종료 event_id={} attempt_id={} kind={} result={} 총_소요_ms={}",
                event.eventId(), attempt.attemptId(), kind, result.getClass().getSimpleName(), elapsedMs(t0));
        return result;
    }

    /**
     * LLM 호출(및 그 앞의 인위적 지연)에 실제로 쓸 수 있는 예산(ms). {@code llm.deadline-ms}만으로 clamp하면
     * 총 처리 기한(A16)을 넘길 수 있어, 발신 몫({@code slack.send-deadline-ms})을 남겨두고 총 잔여 시간으로도
     * 함께 제한한다. 음수일 수 있다 — 그러면 LLM을 호출하지 않고 바로 실패 안내로 간다.
     */
    private long llmBudgetMs(long t0) {
        return Math.min(llmProps.deadlineMs() - elapsedMs(t0), totalRemainingMs(t0) - slackProps.sendDeadlineMs());
    }

    private HandlingResult send(SlackMessageEvent event, AttemptHandle attempt, long t0, String text, String kind,
            boolean finalAttempt) {
        if (!attempt.markSending()) {
            // 소유권을 잃었다는 뜻이다 — 이미 다른 시도가 처리 중이거나 끝났으므로 발신하지 않는다.
            log.warn("SENDING 기록 거절 — 발신 안 함 event_id={} attempt_id={}", event.eventId(), attempt.attemptId());
            return new HandlingResult.Rejected("mark_sending_rejected");
        }

        long remainingMs = Math.min(slackProps.sendDeadlineMs(), totalRemainingMs(t0));
        long sendStart = System.nanoTime();
        SlackSendResult sendResult = slackClient.postMessage(event.channel(), event.replyThreadTs(), text, remainingMs,
                new ReplyMetadata(event.eventId(), attempt.attemptId()));

        attempt.recordPhase("send_ms", elapsedMs(sendStart));

        // sealed 타입 switch — SlackSendResult에 분기가 늘어나면 컴파일 오류로 여기서 바로 드러난다.
        return switch (sendResult) {
            case SlackSendResult.Success s -> {
                if (experimentProps.haltAfterSend()) {
                    // 실험 R3: 발신은 끝났고 완료 기록은 아직이다. 정상 종료 훅도 돌지 않게 halt로 끊는다.
                    log.warn("experiment.halt-after-send — 발신 직후 프로세스 중단 event_id={} attempt_id={}",
                            event.eventId(), attempt.attemptId());
                    halter.run();
                }
                yield new HandlingResult.Delivered(kind, s.ts());
            }
            case SlackSendResult.Failed failed -> {
                String stage = kind + "_send:" + failed.reason();
                // M13 전이표: 답변(kind=answer) 발신의 재시도 가능한 실패는 마지막 시도가 아니면 안내 없이
                // 재시도만 예약한다. 안내(kind=failure_notice) 발신 실패는 재시도 가능 여부와 무관하게 항상
                // 종료다 — 안내가 안내를 또 낳지 않는다("안내 연쇄 없음").
                if ("answer".equals(kind) && failed.retryable() && !finalAttempt) {
                    log.warn("답변 발신 재시도 가능한 실패 → 재시도 예약 event_id={} attempt_id={} stage={}", event.eventId(),
                            attempt.attemptId(), stage);
                    yield new HandlingResult.RetryRequested(stage, failed.retryAfterMs());
                }
                log.warn("발신 실패 event_id={} attempt_id={} stage={} final_attempt={}", event.eventId(),
                        attempt.attemptId(), stage, finalAttempt);
                yield new HandlingResult.Failed(stage, false);
            }
            case SlackSendResult.Unknown unknown -> {
                String stage = kind + "_send:" + unknown.reason();
                log.warn("발신 결과 불명 event_id={} attempt_id={} stage={}", event.eventId(), attempt.attemptId(), stage);
                yield new HandlingResult.Unknown(stage);
            }
        };
    }

    /** t0 기준 전체 처리 기한까지 남은 시간(ms). 음수가 될 수 있다 — 그러면 SlackClient가 발신을 시작하지 않는다(A15). */
    private long totalRemainingMs(long t0) {
        long totalDeadlineNanos = t0 + processingProps.totalDeadlineMs() * NANOS_PER_MS;
        return (totalDeadlineNanos - System.nanoTime()) / NANOS_PER_MS;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / NANOS_PER_MS;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
