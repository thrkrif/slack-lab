package com.slack.lab.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slack.lab.config.RetryProperties;
import com.slack.lab.config.StateProperties;
import com.slack.lab.config.WorkerProperties;
import com.slack.lab.core.model.ClaimOutcome;
import com.slack.lab.core.model.ClaimRequest;
import com.slack.lab.core.model.Finalization;
import com.slack.lab.core.model.HandlingResult;
import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.AttemptHandle;
import com.slack.lab.core.port.ProcessingStateStore;
import com.slack.lab.core.port.QueueDelivery;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 코어의 확정 책임(M19): 브로커 없이 가짜 저장소·가짜 전달만으로 "언제 확정(ACK)하고 언제 놓아주는가"를 검증한다.
 * Redis 통합 테스트는 Lua가 이미 ACK해서 코어의 호출을 지워도 통과했다 — 이 테스트가 그 빈틈을 막는다.
 */
class EventProcessorTest {

    /** ACK·놓아주기 횟수를 세고 예외를 주입할 수 있는 가짜 전달. */
    static final class FakeDelivery implements QueueDelivery {
        int acks;
        int defers;
        boolean ackThrows;
        final SlackMessageEvent event = new SlackMessageEvent("Ev1", "C1", "U1", "<@UB> 안녕", "100.1", null, null, null,
                null);

        @Override public SlackMessageEvent event() { return event; }
        @Override public long gen() { return 0; }
        @Override public long receivedAtMs() { return System.currentTimeMillis(); }
        @Override public String token() { return "tok-1"; }

        @Override
        public void acknowledge() {
            acks++;
            if (ackThrows) {
                throw new IllegalStateException("ack 실패");
            }
        }

        @Override
        public void defer() {
            defers++;
        }
    }

    ProcessingStateStore store;
    SlackEventHandler handler;
    EventProcessor processor;
    FakeDelivery delivery;

    @BeforeEach
    void setUp() {
        store = mock(ProcessingStateStore.class);
        handler = mock(SlackEventHandler.class);
        RetryPolicy retry = new RetryPolicy(new RetryProperties(List.of(5_000L, 30_000L, 120_000L), 3), store);
        processor = new EventProcessor(store, handler, retry, new StateProperties(30_000, 10_000, 7, 24),
                new WorkerProperties(1));
        delivery = new FakeDelivery();
    }

    void claims(ClaimOutcome outcome) {
        when(store.claim(any(ClaimRequest.class))).thenReturn(outcome);
    }

    void handlerReturns(HandlingResult result) {
        when(handler.handle(any(SlackMessageEvent.class), any(AttemptHandle.class), anyBoolean())).thenReturn(result);
    }

    static ClaimOutcome.Claimed claimed() {
        return new ClaimOutcome.Claimed("att-1", 0, false, 0);
    }

    @Test
    void 선점_결과표가_종료로_본_건은_확정한다() {
        claims(new ClaimOutcome.Settled(ClaimOutcome.Reason.DONE));
        processor.process(delivery);
        assertThat(delivery.acks).isEqualTo(1);
        assertThat(delivery.defers).isZero();
    }

    @Test
    void 다른_시도가_처리_중이면_확정하지_않고_놓아준다() {
        claims(new ClaimOutcome.Busy());
        processor.process(delivery);
        assertThat(delivery.acks).isZero();
        assertThat(delivery.defers).isEqualTo(1);
    }

    @Test
    void 보존할_입력이_없으면_확정하지_않고_놓아준다() {
        claims(new ClaimOutcome.NoInput());
        processor.process(delivery);
        assertThat(delivery.acks).isZero();
        assertThat(delivery.defers).isEqualTo(1);
    }

    @Test
    void 선점_호출이_예외로_끝나면_확정하지_않고_놓아준다() {
        when(store.claim(any(ClaimRequest.class))).thenThrow(new IllegalStateException("저장소 다운"));
        assertThatCode(() -> processor.process(delivery)).doesNotThrowAnyException();
        assertThat(delivery.acks).isZero();
        assertThat(delivery.defers).isEqualTo(1);
    }

    @Test
    void 선점_요청에_입력_전체와_전달_토큰이_실린다() {
        claims(new ClaimOutcome.Busy());
        processor.process(delivery);

        ArgumentCaptor<ClaimRequest> captor = ArgumentCaptor.forClass(ClaimRequest.class);
        org.mockito.Mockito.verify(store).claim(captor.capture());
        assertThat(captor.getValue().input()).isSameAs(delivery.event);
        assertThat(captor.getValue().deliveryToken()).isEqualTo("tok-1");
        assertThat(captor.getValue().eventId()).isEqualTo("Ev1");
    }

    @Test
    void 종료_기록이_확인되면_확정한다() {
        claims(claimed());
        handlerReturns(new HandlingResult.Delivered("answer", "200.1"));
        when(store.finalizeAttempt(anyString(), anyString(), anyString(), any(Finalization.class))).thenReturn(true);

        processor.process(delivery);

        assertThat(delivery.acks).isEqualTo(1);
        assertThat(delivery.defers).isZero();
    }

    @Test
    void 종료_기록이_거절되면_확정하지_않고_놓아준다() {
        claims(claimed());
        handlerReturns(new HandlingResult.Delivered("answer", "200.1"));
        when(store.finalizeAttempt(anyString(), anyString(), anyString(), any(Finalization.class))).thenReturn(false);

        processor.process(delivery);

        assertThat(delivery.acks).isZero();
        assertThat(delivery.defers).isEqualTo(1);
    }

    @Test
    void 발신_게이트가_거절하면_상태를_건드리지_않고_놓아준다() {
        claims(claimed());
        handlerReturns(new HandlingResult.Rejected("mark_sending_rejected"));

        processor.process(delivery);

        org.mockito.Mockito.verify(store, org.mockito.Mockito.never())
                .finalizeAttempt(anyString(), anyString(), anyString(), any(Finalization.class));
        assertThat(delivery.acks).isZero();
        assertThat(delivery.defers).isEqualTo(1);
    }

    @Test
    void 종료_기록_호출이_예외로_끝나도_밖으로_던지지_않고_확정하지_않는다() {
        claims(claimed());
        handlerReturns(new HandlingResult.Delivered("answer", "200.1"));
        when(store.finalizeAttempt(anyString(), anyString(), anyString(), any(Finalization.class)))
                .thenThrow(new IllegalStateException("저장소 다운"));

        assertThatCode(() -> processor.process(delivery)).doesNotThrowAnyException();
        assertThat(delivery.acks).isZero();
        assertThat(delivery.defers).isEqualTo(1);
    }

    @Test
    void 확정_뒤_ACK가_예외를_던져도_처리는_정상_종료한다() {
        claims(claimed());
        handlerReturns(new HandlingResult.Delivered("answer", "200.1"));
        when(store.finalizeAttempt(anyString(), anyString(), anyString(), any(Finalization.class))).thenReturn(true);
        delivery.ackThrows = true;

        assertThatCode(() -> processor.process(delivery)).doesNotThrowAnyException();
        assertThat(delivery.acks).isEqualTo(1);
        assertThat(delivery.defers).isZero();
    }

    @Test
    void 재시도_요청은_예약이_확인될_때만_확정한다() {
        claims(claimed());
        handlerReturns(new HandlingResult.RetryRequested("llm_timeout", 0));
        when(store.scheduleRetry(anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyInt(), anyString()))
                .thenReturn(true);

        processor.process(delivery);

        assertThat(delivery.acks).isEqualTo(1);
    }
}
