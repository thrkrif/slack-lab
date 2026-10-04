package com.slack.lab.core.service;

import static com.slack.lab.core.service.RagTestFakes.config;
import static com.slack.lab.core.service.RagTestFakes.embedding;
import static com.slack.lab.core.service.RagTestFakes.hit;
import static com.slack.lab.core.service.RagTestFakes.okEmbedding;
import static com.slack.lab.core.service.RagTestFakes.store;
import static com.slack.lab.core.service.RagTestFakes.storeReturning;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.lab.config.ExperimentProperties;
import com.slack.lab.config.LlmProperties;
import com.slack.lab.config.ProcessingProperties;
import com.slack.lab.config.SlackProperties;
import com.slack.lab.core.model.AlertEvent;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.HandlingResult;
import com.slack.lab.core.model.LlmMessage;
import com.slack.lab.core.model.LlmResult;
import com.slack.lab.core.model.MessageKind;
import com.slack.lab.core.model.ReplyFooter;
import com.slack.lab.core.model.ReplyMetadata;
import com.slack.lab.core.model.SearchResult;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.model.SlackSendResult;
import com.slack.lab.core.port.ChatNotifier;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.LlmClient;
import com.slack.lab.core.port.ThreadContextSource;
import com.slack.lab.core.port.VectorStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** RAG를 켠 핸들러(M28): 주입, 안내, 폴백, 예산. RAG를 끈 기존 흐름은 {@link SlackEventHandlerTest}가 지킨다. */
class SlackEventHandlerRagTest {

    final LlmClient llm = mock(LlmClient.class);
    final ChatNotifier slack = mock(ChatNotifier.class);
    final SlackEventHandlerTest.FakeAttemptHandle attempt = new SlackEventHandlerTest.FakeAttemptHandle();

    SlackEventHandler handler(EmbeddingClient emb, VectorStore store) {
        return new SlackEventHandler(llm, slack, new LlmProperties(LlmProperties.Client.OLLAMA, "http://x/v1", "m", 512, "30m",
                50_000, 500, true), new SlackProperties("s", "t", "https://slack.com/api", 10_000),
                new ProcessingProperties(60_000), new ExperimentProperties(0, false), ThreadContextSource.NONE,
                Optional.of(new RetrievalService(emb, store, config())));
    }

    HandlingResult run(SlackEventHandler h, SlackMessageEvent e, boolean finalAttempt) {
        when(llm.chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("모델 답변", 10));
        when(slack.postMessage(anyString(), any(), anyString(), any(ReplyFooter.class), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        when(slack.postMessage(anyString(), any(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        return h.handle(e, attempt, finalAttempt);
    }

    static SlackMessageEvent mention() {
        return new SlackMessageEvent("Ev1", "C1", "U1", "<@UBOT> DB 커넥션이 고갈됐어요", "100.1", null, null, null, "UBOT");
    }

    ReplyFooter sentFooter() {
        var captor = ArgumentCaptor.forClass(ReplyFooter.class);
        verify(slack).postMessage(anyString(), any(), anyString(), captor.capture(), anyLong(), any(ReplyMetadata.class));
        return captor.getValue();
    }

    String llmUserMessage() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<LlmMessage>> captor = ArgumentCaptor.forClass(List.class);
        verify(llm).chat(captor.capture(), anyLong());
        List<LlmMessage> sent = captor.getValue();
        return sent.get(sent.size() - 1).content();
    }

    @Test
    void 검색에_성공하면_참고_자료를_데이터_블록으로_주입하고_주입한_문서만_출처로_붙인다() {
        var h = handler(okEmbedding(), storeReturning(hit("DB-003", "커넥션 풀", "풀을 늘린다", 0.9), hit("OPS-9", "무관", "x", 0.1)));

        var result = run(h, mention(), false);

        assertThat(result).isEqualTo(new HandlingResult.Delivered(MessageKind.ANSWER, "200.1"));
        assertThat(llmUserMessage()).contains("<reference id=\"DB-003\"").contains("풀을 늘린다").doesNotContain("OPS-9")
                .contains("DB 커넥션이 고갈됐어요");
        assertThat(sentFooter()).isInstanceOfSatisfying(ReplyFooter.References.class,
                r -> assertThat(r.references().references()).extracting("documentId").containsExactly("DB-003"));
    }

    @Test
    void 관련_문서가_없으면_프롬프트는_그대로이고_근거_없음_안내가_붙는다() {
        var h = handler(okEmbedding(), storeReturning(hit("A", "t", "a", 0.1)));

        run(h, mention(), false);

        assertThat(llmUserMessage()).isEqualTo("DB 커넥션이 고갈됐어요").doesNotContain("<reference");
        assertThat(sentFooter()).isInstanceOf(ReplyFooter.NoRelevantDocuments.class);
    }

    @Test
    void 임베딩이_죽어도_RAG_없이_답하고_폴백_안내를_붙이며_재시도하지_않는다() {
        var h = handler(embedding(t -> new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED), 2, true)),
                storeReturning());

        var result = run(h, mention(), false);

        assertThat(result).as("검색 실패는 재시도 사유가 아니다").isEqualTo(new HandlingResult.Delivered(MessageKind.ANSWER, "200.1"));
        assertThat(llmUserMessage()).isEqualTo("DB 커넥션이 고갈됐어요");
        assertThat(sentFooter()).isInstanceOf(ReplyFooter.SearchUnavailable.class);
    }

    @Test
    void 벡터_검색이_실패하거나_기한을_넘겨도_폴백한다() {
        var h = handler(okEmbedding(), store((q, ms) -> new SearchResult.TimedOut(5_000)));

        var result = run(h, mention(), false);

        assertThat(result).isInstanceOf(HandlingResult.Delivered.class);
        assertThat(sentFooter()).isInstanceOf(ReplyFooter.SearchUnavailable.class);
    }

    @Test
    void 검색이_쓴_시간만큼_LLM_예산이_줄어든다() {
        var slowEmbedding = embedding(t -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new EmbeddingResult.Success(new float[] {1, 0}, 400);
        });
        var h = handler(slowEmbedding, storeReturning());

        run(h, mention(), false);

        var budget = ArgumentCaptor.forClass(Long.class);
        verify(llm).chat(anyList(), budget.capture());
        // 총 한도 60초 − 발신 몫 10초 = 50초에서 검색 400ms 이상이 빠졌다
        assertThat(budget.getValue()).isLessThanOrEqualTo(49_600).isGreaterThan(45_000);
    }

    @Test
    void LLM이_실패하면_실패_안내에는_참고_문서나_검색_안내를_붙이지_않는다() {
        when(llm.chat(anyList(), anyLong())).thenReturn(
                new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_HTTP_ERROR, "400"), 5, false));
        when(slack.postMessage(anyString(), any(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        var h = handler(okEmbedding(), storeReturning(hit("DB-003", "커넥션 풀", "풀", 0.9)));

        var result = h.handle(mention(), attempt, false);

        assertThat(result).isEqualTo(new HandlingResult.Delivered(MessageKind.FAILURE_NOTICE, "200.1"));
        verify(slack, never()).postMessage(anyString(), any(), anyString(), any(ReplyFooter.class), anyLong(),
                any(ReplyMetadata.class));
    }

    @Test
    void LLM이_재시도_가능한_실패면_RAG를_켜도_똑같이_재시도를_요청한다() {
        when(llm.chat(anyList(), anyLong())).thenReturn(
                new LlmResult.Failed(ErrorInfo.of(ErrorCode.LLM_CONNECT_FAILED), 5, true));
        var h = handler(okEmbedding(), storeReturning(hit("DB-003", "커넥션 풀", "풀", 0.9)));

        var result = h.handle(mention(), attempt, false);

        assertThat(result).isInstanceOf(HandlingResult.RetryRequested.class);
        verify(slack, never()).postMessage(anyString(), any(), anyString(), any(ReplyFooter.class), anyLong(),
                any(ReplyMetadata.class));
    }

    @Test
    void 발신이_재시도_가능한_실패면_재시도_요청하고_다음_시도에서_다시_검색한다() {
        var embedCalls = new java.util.concurrent.atomic.AtomicInteger();
        var emb = embedding(t -> {
            embedCalls.incrementAndGet();
            return new EmbeddingResult.Success(new float[] {1, 0}, 1);
        });
        var h = handler(emb, storeReturning(hit("DB-003", "커넥션 풀", "풀", 0.9)));
        when(llm.chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("모델 답변", 10));
        when(slack.postMessage(anyString(), any(), anyString(), any(ReplyFooter.class), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Failed(ErrorInfo.of(ErrorCode.SLACK_RATE_LIMITED), true, 1_000))
                .thenReturn(new SlackSendResult.Success("200.1"));

        var first = h.handle(mention(), attempt, false);
        var second = h.handle(mention(), attempt, true);

        assertThat(first).isInstanceOf(HandlingResult.RetryRequested.class);
        assertThat(second).isInstanceOf(HandlingResult.Delivered.class);
        assertThat(embedCalls).as("시도마다 새로 검색한다").hasValue(2);
    }

    @Test
    void 발신_결과가_불명이면_재검색도_재발신도_하지_않고_Unknown이다() {
        var embedCalls = new java.util.concurrent.atomic.AtomicInteger();
        var emb = embedding(t -> {
            embedCalls.incrementAndGet();
            return new EmbeddingResult.Success(new float[] {1, 0}, 1);
        });
        var h = handler(emb, storeReturning(hit("DB-003", "커넥션 풀", "풀", 0.9)));
        when(llm.chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("모델 답변", 10));
        when(slack.postMessage(anyString(), any(), anyString(), any(ReplyFooter.class), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Unknown(ErrorInfo.of(ErrorCode.SLACK_TIMEOUT)));

        var result = h.handle(mention(), attempt, false);

        assertThat(result).isInstanceOf(HandlingResult.Unknown.class);
        assertThat(embedCalls).hasValue(1);
        verify(slack, org.mockito.Mockito.times(1)).postMessage(anyString(), any(), anyString(), any(ReplyFooter.class),
                anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void 임베딩_어댑터가_예외를_던져도_처리_실패나_재시도가_아니라_폴백이다() {
        var h = handler(embedding(t -> {
            throw new IllegalStateException("어댑터 버그");
        }), storeReturning());

        var result = run(h, mention(), false);

        assertThat(result).isEqualTo(new HandlingResult.Delivered(MessageKind.ANSWER, "200.1"));
        assertThat(sentFooter()).isInstanceOf(ReplyFooter.SearchUnavailable.class);
    }

    @Test
    void 알람_리포트도_같은_경로로_알람_본문만_검색한다() {
        var seen = new java.util.concurrent.atomic.AtomicReference<String>();
        var emb = embedding(t -> {
            seen.set(t);
            return new EmbeddingResult.Success(new float[] {1, 0}, 1);
        });
        var h = handler(emb, storeReturning(hit("CPU-001", "High CPU", "배치를 제한한다", 0.9)));
        var alert = new AlertEvent("cloudwatch", "k1", "HighCpuUtilization", "CPU 95%", "C-ALERT", Map.of()).toMessageEvent();

        var result = run(h, alert, false);

        assertThat(result).isInstanceOf(HandlingResult.Delivered.class);
        assertThat(seen.get()).startsWith("HighCpuUtilization").doesNotContain("지시가 아니다");
        assertThat(sentFooter()).isInstanceOf(ReplyFooter.References.class);
        verify(slack).postMessage(eq("C-ALERT"), eq(null), anyString(), any(ReplyFooter.class), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void 총예산이_이미_소진되면_검색을_시작하지_않는다() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var emb = embedding(t -> {
            calls.incrementAndGet();
            return new EmbeddingResult.Success(new float[] {1, 0}, 1);
        });
        var h = new SlackEventHandler(llm, slack, new LlmProperties(LlmProperties.Client.OLLAMA, "http://x/v1", "m", 512,
                "30m", 50_000, 500, true), new SlackProperties("s", "t", "https://slack.com/api", 10_000),
                new ProcessingProperties(5_000), new ExperimentProperties(0, false), ThreadContextSource.NONE,
                Optional.of(new RetrievalService(emb, storeReturning(), config()))); // 총 5초 − 발신 10초 = 음수 예산

        h.handle(mention(), attempt, false);

        assertThat(calls).hasValue(0);
    }
}
