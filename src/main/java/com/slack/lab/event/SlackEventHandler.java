package com.slack.lab.event;

import com.slack.lab.config.ExperimentProperties;
import com.slack.lab.config.ProcessingProperties;
import com.slack.lab.llm.LlmClient;
import com.slack.lab.llm.LlmProperties;
import com.slack.lab.llm.LlmResult;
import com.slack.lab.slack.SlackClient;
import com.slack.lab.slack.SlackProperties;
import com.slack.lab.slack.SlackSendResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * HTTP를 모른다(AGENTS.md 규칙 2, A13 — 이 패키지에 서블릿·스프링 HTTP 타입 import 금지).
 * 입력은 이벤트와 {@link AttemptHandle}, 출력은 {@link HandlingResult}뿐이다. 저장소를 직접 알지 못한다.
 */
@Component
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

    public SlackEventHandler(LlmClient llmClient, SlackClient slackClient, LlmProperties llmProps,
            SlackProperties slackProps, ProcessingProperties processingProps, ExperimentProperties experimentProps) {
        this.llmClient = llmClient;
        this.slackClient = slackClient;
        this.llmProps = llmProps;
        this.slackProps = slackProps;
        this.processingProps = processingProps;
        this.experimentProps = experimentProps;
    }

    public HandlingResult handle(SlackMessageEvent event, AttemptHandle attempt) {
        long t0 = attempt.startNanos();
        // markSending 진입 여부를 기록해둔다 — 예상 못한 예외가 나도 이 값으로 markFailed/markUnknown을 가른다.
        // markSending 전이면 발신이 나가지 않았음이 확실하므로 명확한 실패, 이후라면 발신 여부를 알 수 없다(A10과 같은 원칙).
        boolean[] sendingMarked = {false};
        try {
            // 인위적 지연은 LLM 예산 안에서 소모된다(ADR-7) — 별도 예산을 주지 않는다.
            long slowModeMs = experimentProps.slowModeMs();
            if (slowModeMs > 0) {
                long budgetBeforeSleep = llmProps.deadlineMs() - elapsedMs(t0);
                long sleepMs = Math.min(slowModeMs, Math.max(budgetBeforeSleep, 0));
                if (sleepMs > 0) {
                    sleep(sleepMs);
                }
            }

            // llm.deadline-ms만으로 clamp하면 slow-mode 소모분과 겹쳐 총 처리 기한(A16)을 넘길 수 있다 —
            // 발신 몫(slack.send-deadline-ms)을 남겨두고 총 잔여 시간으로도 함께 제한한다.
            long llmRemainingMs = Math.min(llmProps.deadlineMs() - elapsedMs(t0),
                    totalRemainingMs(t0) - slackProps.sendDeadlineMs());
            LlmResult llmResult = llmRemainingMs > 0
                    ? llmClient.chat(event.promptText(), llmRemainingMs)
                    : new LlmResult.TimedOut(0);

            String text;
            String kind;
            if (llmResult instanceof LlmResult.Success success) {
                kind = "answer";
                text = success.text();
                log.info("LLM 성공 event_id={} attempt_id={} elapsed_ms={}", event.eventId(), attempt.attemptId(),
                        success.elapsedMs());
            } else {
                kind = "failure_notice";
                text = FAILURE_NOTICE;
                String llmStage = llmResult instanceof LlmResult.TimedOut timedOut
                        ? "llm_timeout(elapsed_ms=" + timedOut.elapsedMs() + ")"
                        : "llm_failed:" + ((LlmResult.Failed) llmResult).reason();
                log.warn("LLM 실패 → 실패 안내로 전환 event_id={} attempt_id={} stage={}", event.eventId(), attempt.attemptId(),
                        llmStage);
            }

            HandlingResult result = send(event, attempt, t0, text, kind, sendingMarked);
            log.info("처리 종료 event_id={} attempt_id={} kind={} result={} 총_소요_ms={}",
                    event.eventId(), attempt.attemptId(), kind, result.getClass().getSimpleName(), elapsedMs(t0));
            return result;
        } catch (Exception e) {
            // 예외 가드가 없으면 dedup이 PROCESSING에 영구 고착된다(함정 1·3번) — 반드시 여기서 종료 상태를 확정한다.
            String stage = "unexpected_exception:" + e.getClass().getSimpleName();
            log.error("처리 중 예상 못한 예외 event_id={} attempt_id={} sending_marked={}", event.eventId(),
                    attempt.attemptId(), sendingMarked[0], e);
            if (sendingMarked[0]) {
                attempt.markUnknown(stage);
                return new HandlingResult.Unknown(stage);
            }
            attempt.markFailed(stage);
            return new HandlingResult.Failed(stage);
        }
    }

    private HandlingResult send(SlackMessageEvent event, AttemptHandle attempt, long t0, String text, String kind,
            boolean[] sendingMarked) {
        if (!attempt.markSending()) {
            // 소유권을 잃었다는 뜻이다 — 이미 다른 시도가 처리 중이거나 끝났으므로 발신하지 않는다.
            log.warn("SENDING 기록 거절 — 발신 안 함 event_id={} attempt_id={}", event.eventId(), attempt.attemptId());
            return new HandlingResult.Rejected("mark_sending_rejected");
        }
        sendingMarked[0] = true;

        long remainingMs = Math.min(slackProps.sendDeadlineMs(), totalRemainingMs(t0));
        SlackSendResult sendResult = slackClient.postMessage(event.channel(), event.replyThreadTs(), text, remainingMs);

        // sealed 타입 switch — SlackSendResult에 분기가 늘어나면 컴파일 오류로 여기서 바로 드러난다.
        return switch (sendResult) {
            case SlackSendResult.Success s -> {
                attempt.markCompleted();
                yield new HandlingResult.Delivered(kind);
            }
            case SlackSendResult.Failed failed -> {
                String stage = kind + "_send:" + failed.reason();
                attempt.markFailed(stage);
                log.warn("발신 실패 event_id={} attempt_id={} stage={}", event.eventId(), attempt.attemptId(), stage);
                yield new HandlingResult.Failed(stage);
            }
            case SlackSendResult.Unknown unknown -> {
                String stage = kind + "_send:" + unknown.reason();
                attempt.markUnknown(stage);
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
