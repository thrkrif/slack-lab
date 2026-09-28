package com.slack.lab.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import com.slack.lab.llm.LlmProperties;
import com.slack.lab.llm.LlmResult;
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

        void rejectSending() {
            sendingAllowed = false;
        }
    }

    record Fixture(LlmClient llm, SlackClient slack, FakeAttemptHandle attempt) {
        SlackEventHandler handler(long llmDeadlineMs, long totalDeadlineMs, long slowModeMs) {
            return new SlackEventHandler(llm, slack, llmProps(llmDeadlineMs), slackProps(),
                    new ProcessingProperties(totalDeadlineMs), new ExperimentProperties(slowModeMs, true));
        }
    }

    static Fixture fixture() {
        return new Fixture(mock(LlmClient.class), mock(SlackClient.class), new FakeAttemptHandle());
    }

    @Test
    void LLM_성공하면_답변을_보내고_Delivered를_돌려준다() {
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(eq("C1"), eq("100.1"), eq("답변"), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.1"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt());

        assertThat(result).isEqualTo(new HandlingResult.Delivered("answer", "200.1"));
        verify(f.slack()).postMessage(eq("C1"), eq("100.1"), eq("답변"), anyLong());
    }

    @Test
    void LLM_실패하면_실패_안내를_보내고_Delivered를_돌려준다() {
        // A6: LLM 실패 시 실패 안내 1회 전송
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Failed("connection_refused", 50));
        when(f.slack().postMessage(eq("C1"), eq("100.1"), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.2"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt());

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice", "200.2"));
        verify(f.slack()).postMessage(eq("C1"), eq("100.1"), anyString(), anyLong());
    }

    @Test
    void LLM_성공했지만_Slack이_ok_false면_Failed이고_안내를_연쇄_발신하지_않는다() {
        // A8 + "답변 발신 실패 뒤 안내 연쇄 발신 금지"
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Failed("channel_not_found"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt());

        assertThat(result).isEqualTo(new HandlingResult.Failed("answer_send:channel_not_found", false));
        // 발신은 정확히 1회만 — 실패 뒤 실패 안내를 추가로 보내지 않는다.
        verify(f.slack(), times(1)).postMessage(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void 발신_결과_불명이면_Unknown을_돌려준다() {
        // A10
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Unknown("read_timeout"));

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt());

        assertThat(result).isEqualTo(new HandlingResult.Unknown("answer_send:read_timeout"));
    }

    @Test
    void 처리_총예산이_이미_소진되면_LLM도_실행하지_않고_바로_실패_안내로_간다() {
        // A15·A16: 총 처리 예산이 이미 없으면 LLM 자체 기한(llm.deadline-ms)이 남아 있어도 호출하지 않는다.
        var f = fixture();
        when(f.slack().postMessage(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.longThat(ms -> ms <= 0)))
                .thenReturn(new SlackSendResult.Failed("budget_exhausted"));

        // 총 예산 0ms: 시작 시점 이후 실제로 흐른 시간만큼 이미 초과 상태가 된다.
        var result = f.handler(50_000, 0, 0).handle(event(), f.attempt());

        assertThat(result).isEqualTo(new HandlingResult.Failed("failure_notice_send:budget_exhausted", false));
        verify(f.llm(), never()).chat(anyString(), anyLong()); // 총 예산이 없으므로 LLM 자체를 호출하지 않는다
    }

    @Test
    void LLM_예산은_발신_몫을_남기고_총_처리_기한으로_clamp된다() {
        // A16: llm.deadline-ms(50s)만 보면 넉넉해도, 총 처리 기한이 그보다 작으면(여기선 15s) 발신 몫
        // (slack.send-deadline-ms=10s)을 뺀 나머지로 실제 LLM 호출 기한을 줄여야 한다.
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.5"));

        f.handler(50_000, 15_000, 0).handle(event(), f.attempt());

        ArgumentCaptor<Long> remainingMsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(f.llm()).chat(anyString(), remainingMsCaptor.capture());
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
        when(f.llm().chat(anyString(), anyLong())).thenThrow(new RuntimeException("예상 못한 LLM 클라이언트 오류"));

        assertThatThrownBy(() -> f.handler(50_000, 60_000, 0).handle(event(), f.attempt()))
                .isInstanceOf(RuntimeException.class).hasMessage("예상 못한 LLM 클라이언트 오류");
        assertThat(f.attempt().markSendingCalls).isZero();
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void markSending_후_예외도_그대로_전파되고_그때는_이미_markSending이_불렸다() {
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("예상 못한 Slack 클라이언트 오류"));

        assertThatThrownBy(() -> f.handler(50_000, 60_000, 0).handle(event(), f.attempt()))
                .isInstanceOf(RuntimeException.class);
        assertThat(f.attempt().markSendingCalls).isEqualTo(1);
    }

    @Test
    void LLM_예산이_이미_소진되면_LLM을_호출하지_않고_바로_실패_안내로_간다() {
        var f = fixture();
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.3"));

        // llmDeadlineMs=0 → llmRemainingMs는 항상 <=0
        var result = f.handler(0, 60_000, 0).handle(event(), f.attempt());

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice", "200.3"));
        verify(f.llm(), never()).chat(anyString(), anyLong());
    }

    @Test
    void SENDING_기록이_거절되면_발신하지_않는다() {
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        f.attempt().rejectSending(); // 소유권을 이미 잃은 상황을 흉내 낸다

        var result = f.handler(50_000, 60_000, 0).handle(event(), f.attempt());

        assertThat(result).isEqualTo(new HandlingResult.Rejected("mark_sending_rejected"));
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void slow_mode는_LLM_예산_안에서_소모된다() {
        // ArgumentCaptor로 llmClient.chat()에 실제로 전달된 remainingMs를 잡아 차감을 직접 검증한다.
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.4"));

        long llmDeadlineMs = 50_000;
        long slowModeMs = 100;
        long start = System.nanoTime();
        f.handler(llmDeadlineMs, 60_000, slowModeMs).handle(event(), f.attempt());
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertThat(elapsedMs).isGreaterThanOrEqualTo(slowModeMs);

        ArgumentCaptor<Long> remainingMsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(f.llm()).chat(anyString(), remainingMsCaptor.capture());
        long actualRemainingMs = remainingMsCaptor.getValue();
        // slow-mode로 최소 100ms는 차감돼야 하고, 원래 예산(50_000ms)을 그대로 넘기면 안 된다.
        assertThat(actualRemainingMs).isLessThanOrEqualTo(llmDeadlineMs - slowModeMs);
        assertThat(actualRemainingMs).isGreaterThan(0);
    }
}
