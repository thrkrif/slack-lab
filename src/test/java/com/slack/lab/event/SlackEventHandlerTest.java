package com.slack.lab.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SlackEventHandlerTest {

    static SlackMessageEvent event() {
        return new SlackMessageEvent("Ev1", "C1", "U1", "<@UBOT> 안녕", "100.1", null, null, null, "UBOT");
    }

    static LlmProperties llmProps(long deadlineMs) {
        return new LlmProperties(LlmProperties.Client.OLLAMA, "http://x/v1", "m", 512, "30m", deadlineMs, 500, true);
    }

    static SlackProperties slackProps() {
        return new SlackProperties("s", "t", "https://slack.com/api", 10_000);
    }

    /** 저장소를 몰라도 되는 가짜 핸들. markSending() 호출 여부·거절 여부만 흉내 낸다(M12부터 상태 기록은 워커의 몫). */
    static final class FakeAttemptHandle implements AttemptHandle {
        private final long startNanos = System.nanoTime();
        private boolean sendingAllowed = true;
        private int markSendingCalls;

        @Override
        public String eventId() {
            return "Ev1";
        }

        @Override
        public String attemptId() {
            return "att-1";
        }

        @Override
        public long startNanos() {
            return startNanos;
        }

        @Override
        public boolean markSending() {
            markSendingCalls++;
            return sendingAllowed;
        }

        final java.util.Map<String, Long> phases = new java.util.HashMap<>();

        @Override
        public void recordPhase(String name, long millis) {
            phases.put(name, millis);
        }

        void rejectSending() {
            sendingAllowed = false;
        }
    }

    record Fixture(LlmClient llm, SlackClient slack, FakeAttemptHandle attempt, ThreadContextSource[] ctx) {
        Fixture(LlmClient llm, SlackClient slack, FakeAttemptHandle attempt) {
            this(llm, slack, attempt, new ThreadContextSource[] {ThreadContextSource.NONE});
        }

        SlackEventHandler handler(long llmDeadlineMs, long totalDeadlineMs, long slowModeMs) {
            return handler(llmDeadlineMs, totalDeadlineMs, new ExperimentProperties(slowModeMs, false));
        }

        SlackEventHandler handler(long llmDeadlineMs, long totalDeadlineMs, ExperimentProperties experiment) {
            return new SlackEventHandler(llm, slack, llmProps(llmDeadlineMs), slackProps(),
                    new ProcessingProperties(totalDeadlineMs), experiment, ctx[0]);
        }
    }

    static Fixture fixture() {
        return new Fixture(mock(LlmClient.class), mock(SlackClient.class), new FakeAttemptHandle());
    }

    @Test
    void LLM_성공하면_답변을_보내고_Delivered를_돌려준다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(eq("C1"), eq("100.1"), eq("답변"), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("answer", "200.1"));
        verify(f.slack()).postMessage(eq("C1"), eq("100.1"), eq("답변"), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void LLM_실패하면_실패_안내를_보내고_Delivered를_돌려준다() {
        // A6: LLM 실패 시 실패 안내 1회 전송
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Failed("connection_refused", 50, false));
        when(f.slack().postMessage(eq("C1"), eq("100.1"), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.2"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice", "200.2"));
        verify(f.slack()).postMessage(eq("C1"), eq("100.1"), anyString(), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void LLM_성공했지만_Slack이_ok_false면_Failed이고_안내를_연쇄_발신하지_않는다() {
        // A8 + "답변 발신 실패 뒤 안내 연쇄 발신 금지"
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Failed("channel_not_found"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isEqualTo(new HandlingResult.Failed("answer_send:channel_not_found", false));
        // 발신은 정확히 1회만 — 실패 뒤 실패 안내를 추가로 보내지 않는다.
        verify(f.slack(), times(1)).postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void 발신_결과_불명이면_Unknown을_돌려준다() {
        // A10
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Unknown("read_timeout"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isEqualTo(new HandlingResult.Unknown("answer_send:read_timeout"));
    }

    @Test
    void 처리_총예산이_이미_소진되면_LLM도_실행하지_않고_바로_실패_안내로_간다() {
        // A15·A16: 총 처리 예산이 이미 없으면 LLM 자체 기한(llm.deadline-ms)이 남아 있어도 호출하지 않는다.
        // M13: 이 경로는 합성 TimedOut(재시도 가능)으로 분류되므로, 마지막 시도에서만 재시도 없이 바로
        // 최종 안내로 간다 — 그렇지 않으면 재시도가 예약된다(아래 별도 테스트).
        var f = fixture();
        when(f.slack().postMessage(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.longThat(ms -> ms <= 0), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Failed("budget_exhausted"));

        // 총 예산 0ms: 시작 시점 이후 실제로 흐른 시간만큼 이미 초과 상태가 된다.
        var result = f.handler(50_000, 0, 0).handle(event(), f.attempt(), true);

        assertThat(result).isEqualTo(new HandlingResult.Failed("failure_notice_send:budget_exhausted", false));
        verify(f.llm(), never()).chat(anyList(), anyLong()); // 총 예산이 없으므로 LLM 자체를 호출하지 않는다
    }

    @Test
    void 처리_총예산이_소진됐지만_마지막_시도가_아니면_안내_없이_재시도를_예약한다() {
        // M13: 합성 TimedOut도 재시도 가능한 오류다 — 예산이 바닥난 이번 시도에서 안내를 억지로 밀어넣지
        // 않고, 다음 시도가 새 예산으로 다시 시도하게 둔다.
        var f = fixture();
        var result = f.handler(50_000, 0, 0).handle(event(), f.attempt(), false);

        assertThat(result).isInstanceOf(HandlingResult.RetryRequested.class);
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void LLM_예산은_발신_몫을_남기고_총_처리_기한으로_clamp된다() {
        // A16: llm.deadline-ms(50s)만 보면 넉넉해도, 총 처리 기한이 그보다 작으면(여기선 15s) 발신 몫
        // (slack.send-deadline-ms=10s)을 뺀 나머지로 실제 LLM 호출 기한을 줄여야 한다.
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.5"));

        f.handler(50_000, 15_000, 0).handle(event(), f.attempt(), false);

        ArgumentCaptor<Long> remainingMsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(f.llm()).chat(anyList(), remainingMsCaptor.capture());
        long actualRemainingMs = remainingMsCaptor.getValue();
        // 총 15s - 발신 몫 10s = 약 5s. llm.deadline-ms(50s)를 그대로 넘겼다면 이 상한을 넘었을 것이다.
        assertThat(actualRemainingMs).isLessThanOrEqualTo(5_000);
        assertThat(actualRemainingMs).isGreaterThan(0);
    }

    @Test
    void markSending_전_예외는_그대로_전파된다() {
        // M12부터 예외 가드는 워커의 몫이다 — 핸들러는 그대로 던진다. 워커는 sendingMarked()로
        // Failed/Unknown을 가른다(EventWorker 쪽에서 검증).
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenThrow(new RuntimeException("예상 못한 LLM 클라이언트 오류"));

        assertThatThrownBy(() -> f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false))
                .isInstanceOf(RuntimeException.class).hasMessage("예상 못한 LLM 클라이언트 오류");
        assertThat(f.attempt().markSendingCalls).isZero();
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void markSending_후_예외도_그대로_전파되고_그때는_이미_markSending이_불렸다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenThrow(new RuntimeException("예상 못한 Slack 클라이언트 오류"));

        assertThatThrownBy(() -> f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.attempt().markSendingCalls).isEqualTo(1);
    }

    @Test
    void LLM_예산이_이미_소진되고_마지막_시도면_LLM을_호출하지_않고_바로_실패_안내로_간다() {
        var f = fixture();
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.3"));

        // llmDeadlineMs=0 → llmRemainingMs는 항상 <=0
        var result = f.handler(0, 60_000, 0).handle(event(), f.attempt(), true);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice", "200.3"));
        verify(f.llm(), never()).chat(anyList(), anyLong());
    }

    @Test
    void SENDING_기록이_거절되면_발신하지_않는다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        f.attempt().rejectSending(); // 소유권을 이미 잃은 상황을 흉내 낸다

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isEqualTo(new HandlingResult.Rejected("mark_sending_rejected"));
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void slow_mode는_LLM_예산_안에서_소모된다() {
        // ArgumentCaptor로 llmClient.chat()에 실제로 전달된 remainingMs를 잡아 차감을 직접 검증한다.
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.4"));

        long llmDeadlineMs = 50_000;
        long slowModeMs = 100;
        long start = System.nanoTime();
        f.handler(llmDeadlineMs, 60_000, slowModeMs).handle(event(), f.attempt(), false);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(slowModeMs);

        ArgumentCaptor<Long> remainingMsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(f.llm()).chat(anyList(), remainingMsCaptor.capture());
        long actualRemainingMs = remainingMsCaptor.getValue();
        // slow-mode로 최소 100ms는 차감돼야 하고, 원래 예산(50_000ms)을 그대로 넘기면 안 된다.
        assertThat(actualRemainingMs).isLessThanOrEqualTo(llmDeadlineMs - slowModeMs);
        assertThat(actualRemainingMs).isGreaterThan(0);
    }

    // --- M13 전이표: 재시도 가능한 오류 분류와 finalAttempt

    @Test
    void LLM_재시도_가능한_오류이고_마지막_시도가_아니면_안내_없이_재시도를_요청한다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Failed("connection_refused", 50, true));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isInstanceOf(HandlingResult.RetryRequested.class);
        assertThat(f.attempt().markSendingCalls).isZero(); // 발신 자체를 시도하지 않는다(안내도 없음)
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void LLM_재시도_가능한_오류라도_마지막_시도면_최종_안내를_보낸다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Failed("connection_refused", 50, true));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.9"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), true);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice", "200.9"));
    }

    @Test
    void LLM_영구_오류는_마지막_시도가_아니어도_재시도_없이_즉시_최종_안내를_보낸다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Failed("status=400", 50, false));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.8"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice", "200.8"));
    }

    @Test
    void 답변_발신이_재시도_가능한_일시_실패이고_마지막_시도가_아니면_안내_없이_재시도를_요청한다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Failed("connect_failed:ConnectException", true, 0));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isInstanceOf(HandlingResult.RetryRequested.class);
    }

    @Test
    void 답변_발신이_재시도_가능한_일시_실패라도_마지막_시도면_안내_연쇄_없이_DEAD로_끝난다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Failed("connect_failed:ConnectException", true, 0));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), true);

        assertThat(result).isEqualTo(new HandlingResult.Failed("answer_send:connect_failed:ConnectException", false));
        // 마지막 시도의 답변 발신 실패는 안내를 연쇄 발신하지 않는다 — 발신은 정확히 1회만.
        verify(f.slack(), times(1)).postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class));
    }

    @Test
    void 최종_안내_발신이_실패하면_재시도_가능_여부와_무관하게_항상_DEAD로_끝난다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Failed("status=400", 50, false));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Failed("connect_failed:ConnectException", true, 0));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(result).isEqualTo(
                new HandlingResult.Failed("failure_notice_send:connect_failed:ConnectException", false));
    }

    @Test
    void 발신에_event_id와_attempt_id_메타데이터를_붙인다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));

        f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        var captor = ArgumentCaptor.forClass(ReplyMetadata.class);
        verify(f.slack()).postMessage(anyString(), anyString(), anyString(), anyLong(), captor.capture());
        assertThat(captor.getValue()).isEqualTo(new ReplyMetadata("Ev1", "att-1"));
    }

    @Test
    void halt_after_send는_발신_성공_직후에만_중단한다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        int[] halts = {0};
        var handler = f.handler(50_000, 60_000, new ExperimentProperties(0, true));
        handler.setHalter(() -> halts[0]++);

        handler.handle(event(), f.attempt(), false);

        assertThat(halts[0]).isEqualTo(1);
    }

    @Test
    void halt_after_send가_꺼져_있으면_중단하지_않는다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        int[] halts = {0};
        var handler = f.handler(50_000, 60_000, 0);
        handler.setHalter(() -> halts[0]++);

        handler.handle(event(), f.attempt(), false);

        assertThat(halts[0]).isZero();
    }

    static SlackMessageEvent threadEvent() {
        return new SlackMessageEvent("Ev2", "C1", "U1", "<@UBOT> 그럼 두 번째는?", "100.5", "100.1", null, null, "UBOT");
    }

    @Test
    void 스레드_안_멘션이면_이전_대화가_이번_질문_앞에_붙는다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        f.ctx()[0] = (event, budget) -> java.util.List.of(LlmMessage.user("첫 질문"), LlmMessage.assistant("첫 답"));

        f.handler(50_000, 60_000, 0).handle(threadEvent(), f.attempt(), false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<LlmMessage>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(f.llm()).chat(captor.capture(), anyLong());
        assertThat(captor.getValue()).containsExactly(LlmMessage.user("첫 질문"), LlmMessage.assistant("첫 답"),
                LlmMessage.user("그럼 두 번째는?"));
    }

    @Test
    void 스레드가_아니면_문맥_조회를_하지_않는다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        int[] calls = {0};
        f.ctx()[0] = (event, budget) -> {
            calls[0]++;
            return java.util.List.of();
        };

        f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(calls[0]).isZero();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.List<LlmMessage>> captor = ArgumentCaptor.forClass(java.util.List.class);
        verify(f.llm()).chat(captor.capture(), anyLong());
        assertThat(captor.getValue()).containsExactly(LlmMessage.user("안녕"));
    }

    @Test
    void 문맥_조회가_비어_돌아와도_답변은_정상이다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        f.ctx()[0] = (event, budget) -> java.util.List.of(); // 실패·시간 초과는 구현체가 빈 목록으로 돌려준다

        var result = f.handler(50_000, 60_000, 0).handle(threadEvent(), f.attempt(), false);

        assertThat(result).isInstanceOf(HandlingResult.Delivered.class);
    }

    @Test
    void 단계별_소요를_시도_핸들에_알린다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));

        f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), false);

        assertThat(f.attempt().phases).containsKeys("llm_ms", "send_ms");
        assertThat(f.attempt().phases.values()).allMatch(ms -> ms >= 0);
    }

    @Test
    void LLM이_실패해_발신하지_않으면_send_ms는_기록하지_않는다() {
        var f = fixture();
        when(f.llm().chat(anyList(), anyLong())).thenReturn(new LlmResult.Failed("bad", 5, false));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong(), any(ReplyMetadata.class)))
                .thenReturn(new SlackSendResult.Success("200.1"));
        f.attempt().rejectSending(); // 발신 게이트에서 막혀 send_ms 단계에 이르지 못한다

        f.handler(50_000, 60_000, 0).handle(event(), f.attempt(), true);

        assertThat(f.attempt().phases).containsKey("llm_ms").doesNotContainKey("send_ms");
    }
}
