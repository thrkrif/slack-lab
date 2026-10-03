package com.slack.lab.core.model;

import com.slack.lab.TestFailures;
import com.slack.lab.core.model.MessageKind;
import com.slack.lab.core.model.ProcessingStage;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.ErrorCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 레코드라 정본 생성자를 감출 수 없다 — 대신 상태·보존 목적지 조합을 생성 시점에 검증한다(M11). */
class FinalizationTest {

    @Test
    void UNKNOWN을_목록_보존_없이_만들_수_없다() {
        assertThatThrownBy(() -> new Finalization(Finalization.State.UNKNOWN, Finalization.Destination.NONE,
                "", MessageKind.ANSWER, ProcessingStage.SEND, ErrorInfo.of(ErrorCode.SLACK_API_ERROR))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void COMPLETED를_DLQ_보존과_함께_만들_수_없다() {
        assertThatThrownBy(() -> new Finalization(Finalization.State.COMPLETED, Finalization.Destination.DLQ,
                "1", MessageKind.ANSWER, ProcessingStage.DELIVERED, null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정적_팩토리는_유효한_조합만_만든다() {
        assertThat(Finalization.completed("1", MessageKind.ANSWER).preserveTo()).isEqualTo(Finalization.Destination.NONE);
        assertThat(Finalization.unknown(TestFailures.send("x")).preserveTo()).isEqualTo(Finalization.Destination.RECOVERY);
        assertThat(Finalization.dead(TestFailures.send("x")).preserveTo()).isEqualTo(Finalization.Destination.DLQ);
    }
}
