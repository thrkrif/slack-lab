package com.slack.lab.core.service;

import com.slack.lab.core.port.ChatNotifier;
import com.slack.lab.core.port.AttemptHandle;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.Failure;
import com.slack.lab.core.model.HandlingResult;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.ThreadContextSource;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.ExperimentProperties;
import com.slack.lab.config.ProcessingProperties;
import com.slack.lab.core.port.LlmClient;
import com.slack.lab.core.port.RequestClassifier;
import com.slack.lab.core.model.ClassifyResult;
import com.slack.lab.core.model.RequestKind;
import com.slack.lab.core.model.LlmMessage;
import com.slack.lab.config.LlmProperties;
import com.slack.lab.core.model.LlmResult;
import com.slack.lab.core.model.MessageKind;
import com.slack.lab.core.model.ProcessingStage;
import com.slack.lab.core.model.ReplyMetadata;
import com.slack.lab.config.SlackProperties;
import com.slack.lab.core.model.SlackSendResult;
import com.slack.lab.core.model.ReferenceList;
import com.slack.lab.core.model.ReplyFooter;
import com.slack.lab.core.model.Retrieval;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
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
    private static final String ALERT_FAILURE_NOTICE = "알람 분석을 만들지 못했어요. 원본 알람을 직접 확인해 주세요.";
    private static final String FAILURE_NOTICE = "죄송해요, 지금 답변을 만들지 못했어요. 잠시 후 다시 멘션해 주세요.";
    private static final long NANOS_PER_MS = 1_000_000L;

    private final LlmClient llmClient;
    private final ChatNotifier chatNotifier;
    private final LlmProperties llmProps;
    private final SlackProperties slackProps;
    private final ProcessingProperties processingProps;
    private final ExperimentProperties experimentProps;
    private final ThreadContextSource threadContext;
    // RAG를 끄면 비어 있고 흐름은 3단계 이전과 같다(꺼도 동작). 있으면 스레드 문맥 조회 뒤·LLM 호출 앞에서 한 번 검색한다.
    private final Optional<RetrievalService> retrieval;
    // 분류를 끄면 비어 있고 흐름은 3단계와 같다(꺼도 동작). 있으면 최상위 멘션에만 한 번 분류하고 결과로 검색·되묻기를 가른다.
    private final Optional<RequestClassifier> classifier;
    // 테스트가 프로세스를 죽이지 않고 halt 지점 도달만 확인하려고 바꿔 끼운다. 운영 경로는 항상 halt다.
    private Runnable halter = () -> Runtime.getRuntime().halt(137);

    public SlackEventHandler(LlmClient llmClient, ChatNotifier chatNotifier, LlmProperties llmProps,
            SlackProperties slackProps, ProcessingProperties processingProps, ExperimentProperties experimentProps,
            ThreadContextSource threadContext) {
        this(llmClient, chatNotifier, llmProps, slackProps, processingProps, experimentProps, threadContext,
                Optional.empty());
    }

    public SlackEventHandler(LlmClient llmClient, ChatNotifier chatNotifier, LlmProperties llmProps,
            SlackProperties slackProps, ProcessingProperties processingProps, ExperimentProperties experimentProps,
            ThreadContextSource threadContext, Optional<RetrievalService> retrieval) {
        this(llmClient, chatNotifier, llmProps, slackProps, processingProps, experimentProps, threadContext, retrieval,
                Optional.empty());
    }

    @Autowired
    public SlackEventHandler(LlmClient llmClient, ChatNotifier chatNotifier, LlmProperties llmProps,
            SlackProperties slackProps, ProcessingProperties processingProps, ExperimentProperties experimentProps,
            ThreadContextSource threadContext, Optional<RetrievalService> retrieval,
            Optional<RequestClassifier> classifier) {
        this.llmClient = llmClient;
        this.chatNotifier = chatNotifier;
        this.llmProps = llmProps;
        this.slackProps = slackProps;
        this.processingProps = processingProps;
        this.experimentProps = experimentProps;
        this.threadContext = threadContext;
        this.retrieval = retrieval;
        this.classifier = classifier;
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

        // RAG(3단계): 검색 시간도 같은 LLM 단계 예산에서 쓰고, 쓴 만큼 아래 LLM 예산이 줄어든다. 검색이 실패하거나 기한을 넘겨도
        // 처리 실패나 재시도 사유가 아니다 — RAG 없이 답하고 서버가 안내를 붙인다. 시도마다 새로 검색한다(결과를 저장하지 않는다).
        ReplyFooter footer = ReplyFooter.NONE;
        String question = event.promptText();
        RequestKind requestKind = classify(event, attempt, t0);
        if (requestKind == RequestKind.NEEDS_INFO) {
            // 정보 부족: 검색 없이 부족한 정보를 되묻는다. 푸터도 없다(근거를 쓰지 않았으므로 출처를 붙이지 않는다).
            question = AskBackPrompt.compose(event.promptText());
        }
        if (retrieval.isPresent() && requestKind == RequestKind.TROUBLE) {
            long ragStart = System.nanoTime();
            Retrieval found = retrieval.get().retrieve(event.promptText(), llmBudgetMs(t0));
            attempt.recordPhase("rag_ms", elapsedMs(ragStart));
            switch (found) {
                case Retrieval.Found f -> {
                    try {
                        question = RagPrompt.compose(event.promptText(), f.hits());
                        footer = new ReplyFooter.References(ReferenceList.fromInjected(f.hits()));
                        log.info("RAG 검색 event_id={} attempt_id={} result=found injected={} elapsed_ms={}", event.eventId(),
                                attempt.attemptId(), f.hits().size(), f.elapsedMs());
                    } catch (RuntimeException e) {
                        // 프롬프트 조립 실패도 RAG 없이 답하는 폴백이다 — 처리 실패·재시도로 번지게 두지 않는다.
                        question = event.promptText();
                        footer = new ReplyFooter.SearchUnavailable();
                        log.warn("RAG 프롬프트 조립 실패 → RAG 없이 진행 event_id={} attempt_id={} reason={}", event.eventId(),
                                attempt.attemptId(), e.getClass().getSimpleName());
                    }
                }
                case Retrieval.NoRelevant n -> {
                    footer = new ReplyFooter.NoRelevantDocuments();
                    log.info("RAG 검색 event_id={} attempt_id={} result=none elapsed_ms={}", event.eventId(),
                            attempt.attemptId(), n.elapsedMs());
                }
                case Retrieval.Unavailable u -> {
                    footer = new ReplyFooter.SearchUnavailable();
                    log.warn("RAG 검색 불가 → RAG 없이 진행 event_id={} attempt_id={} error={} elapsed_ms={}", event.eventId(),
                            attempt.attemptId(), u.error().text(), u.elapsedMs());
                }
            }
        }
        messages.add(LlmMessage.user(question));

        long llmRemainingMs = llmBudgetMs(t0);
        long llmStart = System.nanoTime();
        LlmResult llmResult = llmRemainingMs > 0
                ? llmClient.chat(messages, llmRemainingMs)
                : new LlmResult.TimedOut(0);
        attempt.recordPhase("llm_ms", elapsedMs(llmStart));

        String text;
        MessageKind kind;
        ReplyFooter replyFooter = footer;
        if (llmResult instanceof LlmResult.Success success) {
            kind = MessageKind.ANSWER;
            // 되묻기 답글은 3b가 물음표를 겹쳐 쓰는 형식 결함이 있어(M35 실측 "…나요? ?") 중복만 정리한다. 내용은 건드리지 않는다.
            text = requestKind == RequestKind.NEEDS_INFO ? AskBackPrompt.tidy(success.text()) : success.text();
            log.info("LLM 성공 event_id={} attempt_id={} elapsed_ms={}", event.eventId(), attempt.attemptId(),
                    success.elapsedMs());
        } else {
            // 재시도 가능한 오류(연결 실패·5xx·기한 초과)이고 마지막 시도가 아니면, 안내 없이 재시도만
            // 예약한다(M13 전이표 "LLM 재시도 가능, 마지막 시도 전"). 영구 오류이거나 마지막 시도면
            // 재시도하지 않고 바로 최종 안내로 간다 — 안내는 재시도 사슬을 만들지 않는다.
            boolean retryable = llmResult instanceof LlmResult.TimedOut
                    || ((LlmResult.Failed) llmResult).retryable();
            ErrorInfo llmError = llmResult instanceof LlmResult.TimedOut timedOut
                    ? ErrorInfo.of(ErrorCode.LLM_TIMEOUT, "elapsed_ms=" + timedOut.elapsedMs())
                    : ((LlmResult.Failed) llmResult).error();
            if (retryable && !finalAttempt) {
                log.warn("LLM 재시도 가능한 실패 → 재시도 예약 event_id={} attempt_id={} error={}", event.eventId(),
                        attempt.attemptId(), llmError.text());
                return new HandlingResult.RetryRequested(
                        new Failure(ProcessingStage.LLM, MessageKind.ANSWER, llmError), 0);
            }
            kind = MessageKind.FAILURE_NOTICE;
            replyFooter = ReplyFooter.NONE; // 실패 안내에는 참고 문서·검색 안내를 붙이지 않는다
            // 알람 리포트에는 "다시 멘션" 안내가 맞지 않는다(멘션할 원 메시지가 없다).
            text = event.isAlert() ? ALERT_FAILURE_NOTICE : FAILURE_NOTICE;
            log.warn("LLM 실패 → 실패 안내로 전환 event_id={} attempt_id={} error={} final_attempt={}", event.eventId(),
                    attempt.attemptId(), llmError.text(), finalAttempt);
        }

        HandlingResult result = send(event, attempt, t0, text, replyFooter, kind, finalAttempt);
        log.info("처리 종료 event_id={} attempt_id={} kind={} result={} 총_소요_ms={}",
                event.eventId(), attempt.attemptId(), kind.code(), result.getClass().getSimpleName(), elapsedMs(t0));
        return result;
    }

    /**
     * 요청 종류를 정한다(4단계). 분류는 최상위 멘션에만 적용한다: 알람은 정의상 장애이고, 스레드 안 이벤트는 앞 대화에 기대는
     * 후속이라 단독 질문으로 분류하면 틀린다(되묻기에 대한 사용자 답변도 여기서 장애 질문으로 가서 스레드 문맥과 함께 검색된다).
     * 분류가 실패하거나 예외를 던져도 처리 실패·재시도 사유가 아니다 — 장애 질문(3단계 흐름)으로 폴백한다(fail-open).
     * 시도마다 다시 분류하되(저장하지 않는다) 시도별 라벨을 로그에 남긴다.
     */
    private RequestKind classify(SlackMessageEvent event, AttemptHandle attempt, long t0) {
        if (classifier.isEmpty() || event.isAlert() || event.threadTs() != null) {
            return RequestKind.TROUBLE;
        }
        long start = System.nanoTime();
        RequestKind kind = RequestKind.TROUBLE;
        try {
            ClassifyResult result = classifier.get().classify(event.promptText(), llmBudgetMs(t0));
            if (result instanceof ClassifyResult.Classified c) {
                kind = c.kind();
                log.info("요청 분류 event_id={} attempt_id={} request_kind={} elapsed_ms={}", event.eventId(),
                        attempt.attemptId(), kind, c.elapsedMs());
            } else if (result instanceof ClassifyResult.Failed f) {
                log.warn("요청 분류 실패 → 장애 질문으로 진행 event_id={} attempt_id={} classify_fallback={} elapsed_ms={}",
                        event.eventId(), attempt.attemptId(), f.error().text(), f.elapsedMs());
            }
        } catch (RuntimeException e) {
            // 포트 계약은 예외를 던지지 않지만, 부가 기능이 처리 전체를 막으면 안 된다.
            log.warn("요청 분류 예외 → 장애 질문으로 진행 event_id={} attempt_id={} classify_fallback={}", event.eventId(),
                    attempt.attemptId(), e.getClass().getSimpleName());
        }
        attempt.recordPhase("classify_ms", elapsedMs(start));
        return kind;
    }

    /**
     * LLM 호출(및 그 앞의 인위적 지연)에 실제로 쓸 수 있는 예산(ms). {@code llm.deadline-ms}만으로 clamp하면
     * 총 처리 기한(A16)을 넘길 수 있어, 발신 몫({@code slack.send-deadline-ms})을 남겨두고 총 잔여 시간으로도
     * 함께 제한한다. 음수일 수 있다 — 그러면 LLM을 호출하지 않고 바로 실패 안내로 간다.
     */
    private long llmBudgetMs(long t0) {
        return Math.min(llmProps.deadlineMs() - elapsedMs(t0), totalRemainingMs(t0) - slackProps.sendDeadlineMs());
    }

    private HandlingResult send(SlackMessageEvent event, AttemptHandle attempt, long t0, String text, ReplyFooter footer,
            MessageKind kind, boolean finalAttempt) {
        attempt.recordKind(kind);
        if (!attempt.markSending()) {
            // 소유권을 잃었다는 뜻이다 — 이미 다른 시도가 처리 중이거나 끝났으므로 발신하지 않는다.
            log.warn("SENDING 기록 거절 — 발신 안 함 event_id={} attempt_id={}", event.eventId(), attempt.attemptId());
            return new HandlingResult.Rejected(ErrorInfo.of(ErrorCode.SEND_REJECTED, "mark_sending"));
        }

        long remainingMs = Math.min(slackProps.sendDeadlineMs(), totalRemainingMs(t0));
        long sendStart = System.nanoTime();
        ReplyMetadata metadata = new ReplyMetadata(event.eventId(), attempt.attemptId());
        // 덧붙임이 없으면 3단계 이전과 같은 호출 경로를 쓴다.
        SlackSendResult sendResult = footer.isNone()
                ? chatNotifier.postMessage(event.channel(), event.replyThreadTs(), text, remainingMs, metadata)
                : chatNotifier.postMessage(event.channel(), event.replyThreadTs(), text, footer, remainingMs, metadata);

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
                Failure failure = new Failure(ProcessingStage.SEND, kind, failed.error());
                // M13 전이표: 답변(kind=answer) 발신의 재시도 가능한 실패는 마지막 시도가 아니면 안내 없이
                // 재시도만 예약한다. 안내(kind=failure_notice) 발신 실패는 재시도 가능 여부와 무관하게 항상
                // 종료다 — 안내가 안내를 또 낳지 않는다("안내 연쇄 없음").
                if (kind == MessageKind.ANSWER && failed.retryable() && !finalAttempt) {
                    log.warn("답변 발신 재시도 가능한 실패 → 재시도 예약 event_id={} attempt_id={} error={}", event.eventId(),
                            attempt.attemptId(), failed.error().text());
                    yield new HandlingResult.RetryRequested(failure, failed.retryAfterMs());
                }
                log.warn("발신 실패 event_id={} attempt_id={} kind={} error={} final_attempt={}", event.eventId(),
                        attempt.attemptId(), kind.code(), failed.error().text(), finalAttempt);
                yield new HandlingResult.Failed(failure);
            }
            case SlackSendResult.Unknown unknown -> {
                log.warn("발신 결과 불명 event_id={} attempt_id={} kind={} error={}", event.eventId(), attempt.attemptId(),
                        kind.code(), unknown.error().text());
                yield new HandlingResult.Unknown(new Failure(ProcessingStage.SEND, kind, unknown.error()));
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
