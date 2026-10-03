package com.slack.lab.core.service;

import static org.assertj.core.api.Assertions.assertThatCode;

import com.slack.lab.core.port.BacklogProbe;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BacklogReporterTest {

    @Test
    void 조회가_실패해도_보고_주기를_멈추는_예외를_내보내지_않는다() {
        BacklogProbe broken = () -> {
            throw new IllegalStateException("queue_depth_unavailable");
        };
        BacklogProbe ok = () -> Map.of("retry", 1L);

        assertThatCode(() -> new BacklogReporter(List.of(ok, broken)).report()).doesNotThrowAnyException();
        assertThatCode(() -> new BacklogReporter(List.of(ok)).report()).doesNotThrowAnyException();
    }
}
