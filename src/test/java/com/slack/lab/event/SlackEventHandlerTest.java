package com.slack.lab.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    /**
     * 실제 EventDeduplicator를 써서 진짜 전이 규칙(CAS·합법 전이)으로 검증한다 — 수작업 가짜보다 신뢰도가 높다.
     * 시계는 반드시 실제 {@code System.nanoTime()}을 써야 한다 — {@link SlackEventHandler}가 기한 계산에
     * 실제 시계를 쓰므로, dedup의 가짜 시계가 어긋나면 모든 시나리오가 예산 소진으로 오분류된다.
     */
    record Fixture(LlmClient llm, SlackClient slack, EventDeduplicator dedup) {
        SlackEventHandler handler(long llmDeadlineMs, long totalDeadlineMs, long slowModeMs) {
            return new SlackEventHandler(llm, slack, llmProps(llmDeadlineMs), slackProps(),
                    new ProcessingProperties(totalDeadlineMs), new ExperimentProperties(slowModeMs, true));
        }

        AttemptHandle claim() {
            return ((ClaimResult.Claimed) dedup.claim("Ev1")).handle();
        }
    }

    static Fixture fixture() {
        return new Fixture(mock(LlmClient.class), mock(SlackClient.class), new EventDeduplicator(true, System::nanoTime));
    }

    @Test
    void LLM_성공하면_답변을_보내고_COMPLETED가_된다() {
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(eq("C1"), eq("100.1"), eq("답변"), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.1"));

        var attempt = f.claim();
        var result = f.handler(50_000, 60_000, 0).handle(event(), attempt);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("answer"));
        assertThat(f.dedup().stateOf("Ev1")).isEqualTo(ProcessingState.COMPLETED);
        verify(f.slack()).postMessage(eq("C1"), eq("100.1"), eq("답변"), anyLong());
    }

    @Test
    void LLM_실패하면_실패_안내를_보내고_COMPLETED가_된다() {
        // A6: LLM 실패 시 실패 안내 1회 전송
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Failed("connection_refused", 50));
        when(f.slack().postMessage(eq("C1"), eq("100.1"), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.2"));

        var attempt = f.claim();
        var result = f.handler(50_000, 60_000, 0).handle(event(), attempt);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice"));
        assertThat(f.dedup().stateOf("Ev1")).isEqualTo(ProcessingState.COMPLETED);
        verify(f.slack()).postMessage(eq("C1"), eq("100.1"), anyString(), anyLong());
    }

    @Test
    void LLM_성공했지만_Slack이_ok_false면_FAILED이고_안내를_연쇄_발신하지_않는다() {
        // A8 + "답변 발신 실패 뒤 안내 연쇄 발신 금지"
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Failed("channel_not_found"));

        var attempt = f.claim();
        var result = f.handler(50_000, 60_000, 0).handle(event(), attempt);

        assertThat(result).isEqualTo(new HandlingResult.Failed("answer_send:channel_not_found"));
        assertThat(f.dedup().stateOf("Ev1")).isEqualTo(ProcessingState.FAILED);
        // 발신은 정확히 1회만 — 실패 뒤 실패 안내를 추가로 보내지 않는다.
        verify(f.slack(), org.mockito.Mockito.times(1)).postMessage(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void 발신_결과_불명이면_UNKNOWN이_된다() {
        // A10
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 100));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Unknown("read_timeout"));

        var attempt = f.claim();
        var result = f.handler(50_000, 60_000, 0).handle(event(), attempt);

        assertThat(result).isEqualTo(new HandlingResult.Unknown("answer_send:read_timeout"));
        assertThat(f.dedup().stateOf("Ev1")).isEqualTo(ProcessingState.UNKNOWN);
    }

    @Test
    void 처리_총예산이_이미_소진되면_LLM도_실행하지_않고_바로_실패_안내로_간다() {
        // A15·A16: 총 처리 예산이 이미 없으면 LLM 자체 기한(llm.deadline-ms)이 남아 있어도 호출하지 않는다.
        // (code-reviewer MEDIUM 지적 반영 전에는 llm.deadline-ms만으로 clamp해 총 기한을 무시했다.)
        var f = fixture();
        when(f.slack().postMessage(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.longThat(ms -> ms <= 0)))
                .thenReturn(new SlackSendResult.Failed("budget_exhausted"));

        var attempt = f.claim();
        // 총 예산 0ms: claim 시점 이후 실제로 흐른 시간만큼 이미 초과 상태가 된다.
        var result = f.handler(50_000, 0, 0).handle(event(), attempt);

        assertThat(result).isEqualTo(new HandlingResult.Failed("failure_notice_send:budget_exhausted"));
        assertThat(f.dedup().stateOf("Ev1")).isEqualTo(ProcessingState.FAILED);
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

        var attempt = f.claim();
        f.handler(50_000, 15_000, 0).handle(event(), attempt);

        ArgumentCaptor<Long> remainingMsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(f.llm()).chat(anyString(), remainingMsCaptor.capture());
        long actualRemainingMs = remainingMsCaptor.getValue();
        // 총 15s - 발신 몫 10s = 약 5s. llm.deadline-ms(50s)를 그대로 넘겼다면 이 상한을 넘었을 것이다.
        assertThat(actualRemainingMs).isLessThanOrEqualTo(5_000);
        assertThat(actualRemainingMs).isGreaterThan(0);
    }

    @Test
    void markSending_전_예외는_FAILED로_확정되고_PROCESSING에_고착되지_않는다() {
        // HIGH 수정의 핵심 회귀 테스트: 예외 가드가 없으면 이 시나리오에서 dedup이 PROCESSING에 영원히 갇힌다.
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenThrow(new RuntimeException("예상 못한 LLM 클라이언트 오류"));

        var attempt = f.claim();
        var result = f.handler(50_000, 60_000, 0).handle(event(), attempt);

        assertThat(result).isInstanceOf(HandlingResult.Failed.class);
        assertThat(f.dedup().stateOf("Ev1")).isEqualTo(ProcessingState.FAILED);
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void markSending_후_예외는_UNKNOWN으로_확정되고_자동_재발신_대상이_아니다() {
        // A10과 같은 원칙: 발신 여부를 확인할 수 없으면 UNKNOWN이지 FAILED(재시도 가능)가 아니다.
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenThrow(new RuntimeException("예상 못한 Slack 클라이언트 오류"));

        var attempt = f.claim();
        var result = f.handler(50_000, 60_000, 0).handle(event(), attempt);

        assertThat(result).isInstanceOf(HandlingResult.Unknown.class);
        assertThat(f.dedup().stateOf("Ev1")).isEqualTo(ProcessingState.UNKNOWN);
    }

    @Test
    void LLM_예산이_이미_소진되면_LLM을_호출하지_않고_바로_실패_안내로_간다() {
        var f = fixture();
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.3"));

        var attempt = f.claim();
        // llmDeadlineMs=0 → llmRemainingMs는 항상 <=0
        var result = f.handler(0, 60_000, 0).handle(event(), attempt);

        assertThat(result).isEqualTo(new HandlingResult.Delivered("failure_notice"));
        verify(f.llm(), never()).chat(anyString(), anyLong());
    }

    @Test
    void SENDING_기록이_거절되면_발신하지_않는다() {
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));

        var attempt = f.claim();
        attempt.markFailed("이미 다른 경로로 종료됨"); // 소유권을 미리 종료시켜 markSending이 거절되게 만든다

        var result = f.handler(50_000, 60_000, 0).handle(event(), attempt);

        assertThat(result).isEqualTo(new HandlingResult.Rejected("mark_sending_rejected"));
        verify(f.slack(), never()).postMessage(anyString(), anyString(), anyString(), anyLong());
    }

    @Test
    void slow_mode는_LLM_예산_안에서_소모된다() {
        // 이전에는 벽시계 경과만 봤다 — 그건 sleep()이 불렸다는 증거일 뿐, LLM에 실제로 줄어든 예산이
        // 전달됐다는 증거가 아니다(예: sleep 후에도 원래 deadlineMs를 그대로 넘기는 결함을 못 잡는다).
        // ArgumentCaptor로 llmClient.chat()에 실제로 전달된 remainingMs를 잡아 차감을 직접 검증한다.
        var f = fixture();
        when(f.llm().chat(anyString(), anyLong())).thenReturn(new LlmResult.Success("답변", 10));
        when(f.slack().postMessage(anyString(), anyString(), anyString(), anyLong()))
                .thenReturn(new SlackSendResult.Success("200.4"));

        long llmDeadlineMs = 50_000;
        long slowModeMs = 100;
        var attempt = f.claim();
        long start = System.nanoTime();
        f.handler(llmDeadlineMs, 60_000, slowModeMs).handle(event(), attempt);
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
