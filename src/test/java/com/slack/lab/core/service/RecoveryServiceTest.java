package com.slack.lab.core.service;

import com.slack.lab.core.model.ReplyMatch;
import com.slack.lab.core.model.ScanResult;
import com.slack.lab.core.port.ThreadLookup;
import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.port.RecoveryStore.Entry;
import com.slack.lab.core.port.RecoveryStore.ThreadRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.lab.core.model.RecoveryOutcome;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecoveryServiceTest {

    RecoveryStore store = mock(RecoveryStore.class);
    ThreadLookup threads = mock(ThreadLookup.class);
    RecoveryService service = new RecoveryService(store, threads);
    ByteArrayOutputStream buf = new ByteArrayOutputStream();
    PrintStream out = new PrintStream(buf, true, StandardCharsets.UTF_8);

    String output() {
        return buf.toString(StandardCharsets.UTF_8);
    }

    @Test
    void 인자가_없거나_모르는_명령이면_사용법과_종료코드_2다() {
        assertThat(service.execute(List.of(), out)).isEqualTo(2);
        assertThat(service.execute(List.of("bogus"), out)).isEqualTo(2);
        assertThat(service.execute(List.of("check"), out)).isEqualTo(2);
        assertThat(output()).contains("사용법");
    }

    @Test
    void 목록은_건수와_event_id를_보여준다() {
        when(store.list()).thenReturn(List.of(new Entry("recovery", "Ev1", 1_000L,
                Map.of("state", "UNKNOWN", "stage", "answer_send:read_timeout", "gen", "0"), "x")));

        assertThat(service.execute(List.of("list"), out)).isZero();

        assertThat(output()).contains("Ev1").contains("UNKNOWN").contains("합계 1건");
    }

    @Test
    void 목록이_비어_있으면_그렇게_알린다() {
        when(store.list()).thenReturn(List.of());
        assertThat(service.execute(List.of("list"), out)).isZero();
        assertThat(output()).contains("미해결 건 없음");
    }

    @Test
    void check가_답글을_찾으면_ts와_다음_명령을_안내한다() {
        when(store.stateOf("Ev1")).thenReturn(Map.of("state", "UNKNOWN", "stage", "s", "gen", "0"));
        when(store.threadRef("Ev1")).thenReturn(Optional.of(new ThreadRef("C1", "1.1")));
        when(threads.findReplies("C1", "1.1", "Ev1"))
                .thenReturn(new ScanResult(List.of(new ReplyMatch("2.2", "att-1")), null, true));

        assertThat(service.execute(List.of("check", "Ev1"), out)).isZero();

        assertThat(output()).contains("답글 발견 ts=2.2").contains("resolve-completed Ev1");
    }

    @Test
    void check가_끝까지_읽고도_없으면_미전송으로_보고_reprocess를_안내한다() {
        when(store.stateOf("Ev1")).thenReturn(Map.of("state", "UNKNOWN"));
        when(store.threadRef("Ev1")).thenReturn(Optional.of(new ThreadRef("C1", "1.1")));
        when(threads.findReplies("C1", "1.1", "Ev1")).thenReturn(new ScanResult(List.of(), null, true));

        assertThat(service.execute(List.of("check", "Ev1"), out)).isZero();

        assertThat(output()).contains("답글 없음").contains("reprocess Ev1 confirm-unsent");
    }

    @Test
    void check가_끝까지_읽지_못했으면_없다고_단정하지_않고_실패로_끝낸다() {
        when(store.stateOf("Ev1")).thenReturn(Map.of("state", "UNKNOWN"));
        when(store.threadRef("Ev1")).thenReturn(Optional.of(new ThreadRef("C1", "1.1")));
        when(threads.findReplies("C1", "1.1", "Ev1")).thenReturn(new ScanResult(List.of(), null, false));

        assertThat(service.execute(List.of("check", "Ev1"), out)).isEqualTo(1);

        assertThat(output()).contains("단정할 수 없다").doesNotContain("reprocess Ev1");
    }

    @Test
    void check가_조회_실패면_판단하지_않고_스코프를_안내한다() {
        when(store.stateOf("Ev1")).thenReturn(Map.of("state", "UNKNOWN"));
        when(store.threadRef("Ev1")).thenReturn(Optional.of(new ThreadRef("C1", "1.1")));
        when(threads.findReplies("C1", "1.1", "Ev1")).thenReturn(new ScanResult(List.of(), "missing_scope", false));

        assertThat(service.execute(List.of("check", "Ev1"), out)).isEqualTo(1);

        assertThat(output()).contains("missing_scope").contains("channels:history");
    }

    @Test
    void check는_상태가_없거나_스레드_위치를_모르면_조회하지_않는다() {
        when(store.stateOf("none")).thenReturn(Map.of());
        assertThat(service.execute(List.of("check", "none"), out)).isEqualTo(1);

        when(store.stateOf("Ev2")).thenReturn(Map.of("state", "DEAD"));
        when(store.threadRef("Ev2")).thenReturn(Optional.empty());
        assertThat(service.execute(List.of("check", "Ev2"), out)).isEqualTo(1);

        verify(threads, never()).findReplies(anyString(), anyString(), anyString());
    }

    @Test
    void reprocess는_확인_인자가_없으면_저장소를_건드리지_않는다() {
        assertThat(service.execute(List.of("reprocess", "Ev1"), out)).isEqualTo(2);
        assertThat(service.execute(List.of("reprocess", "Ev1", "yes"), out)).isEqualTo(2);

        verify(store, never()).reprocess(anyString());
        assertThat(output()).contains("check").contains("중복 답글");
    }

    @Test
    void reprocess는_확인_인자가_있을_때만_승인하고_거절은_종료코드_1이다() {
        when(store.reprocess("Ev1")).thenReturn(new RecoveryOutcome("OK", "1"));
        when(store.reprocess("Ev2")).thenReturn(new RecoveryOutcome("BAD_STATE", "COMPLETED"));

        assertThat(service.execute(List.of("reprocess", "Ev1", "confirm-unsent"), out)).isZero();
        assertThat(service.execute(List.of("reprocess", "Ev2", "confirm-unsent"), out)).isEqualTo(1);
        assertThat(output()).contains("COMPLETED");
    }

    @Test
    void resolve_completed와_close는_저장소_결과를_그대로_종료코드로_옮긴다() {
        when(store.resolveCompleted("Ev1", "2.2")).thenReturn(new RecoveryOutcome("OK", ""));
        when(store.close("Ev1")).thenReturn(new RecoveryOutcome("NOT_FOUND", ""));

        assertThat(service.execute(List.of("resolve-completed", "Ev1", "2.2"), out)).isZero();
        assertThat(service.execute(List.of("close", "Ev1"), out)).isEqualTo(1);
        assertThat(service.execute(List.of("resolve-completed", "Ev1"), out)).isEqualTo(2);
    }

    @Test
    void check는_이미_해결된_건이면_스레드를_조회하지_않는다() {
        when(store.stateOf("Ev1")).thenReturn(Map.of("state", "COMPLETED", "stage", "manual_resolved"));

        assertThat(service.execute(List.of("check", "Ev1"), out)).isZero();

        assertThat(output()).contains("이미 해결된 건");
        verify(threads, never()).findReplies(anyString(), anyString(), anyString());
    }
}
