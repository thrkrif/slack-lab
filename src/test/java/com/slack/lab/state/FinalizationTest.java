package com.slack.lab.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** 레코드라 정본 생성자를 감출 수 없다 — 대신 상태·보존 목적지 조합을 생성 시점에 검증한다(M11). */
class FinalizationTest {

    @Test
    void UNKNOWN을_목록_보존_없이_만들_수_없다() {
        assertThatThrownBy(() -> new Finalization(Finalization.State.UNKNOWN, Finalization.Destination.NONE,
                "", "answer", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void COMPLETED를_DLQ_보존과_함께_만들_수_없다() {
        assertThatThrownBy(() -> new Finalization(Finalization.State.COMPLETED, Finalization.Destination.DLQ,
                "1", "answer", "x")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 정적_팩토리는_유효한_조합만_만든다() {
        assertThat(Finalization.completed("1", "answer").preserveTo()).isEqualTo(Finalization.Destination.NONE);
        assertThat(Finalization.unknown("answer", "x").preserveTo()).isEqualTo(Finalization.Destination.RECOVERY);
        assertThat(Finalization.dead("answer", "x").preserveTo()).isEqualTo(Finalization.Destination.DLQ);
    }
}
