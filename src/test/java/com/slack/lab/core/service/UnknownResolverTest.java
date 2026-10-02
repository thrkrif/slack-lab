package com.slack.lab.core.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.slack.lab.core.model.RecoveryOutcome;
import com.slack.lab.core.model.ReplyMatch;
import com.slack.lab.core.model.ScanResult;
import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.port.RecoveryStore.Entry;
import com.slack.lab.core.port.RecoveryStore.ThreadRef;
import com.slack.lab.core.port.ThreadLookup;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 결과 불명 자동 조회(ADR-9 6항): 읽기 전용으로 답글을 찾으면 완료 처리하고, 그 밖에는 아무것도 바꾸지 않는다. 재발신은 없다. */
class UnknownResolverTest {

    RecoveryStore store = mock(RecoveryStore.class);
    ThreadLookup threads = mock(ThreadLookup.class);
    UnknownResolver resolver = new UnknownResolver(store, threads, 10_000);

    static Entry entry(String list, String id, String state, long ageMs) {
        return new Entry(list, id, System.currentTimeMillis() - ageMs, Map.of("state", state), "x");
    }

    @BeforeEach
    void setUp() {
        when(store.threadRef(anyString())).thenReturn(Optional.of(new ThreadRef("C1", "1.1")));
    }

    @Test
    void 답글을_찾으면_그_ts로_완료_처리한다() {
        when(store.list()).thenReturn(List.of(entry("recovery", "Ev1", "UNKNOWN", 60_000)));
        when(threads.findReplies("C1", "1.1", "Ev1"))
                .thenReturn(new ScanResult(List.of(new ReplyMatch("2.2", "att")), null, true));
        when(store.resolveCompletedAutomatically("Ev1", "2.2")).thenReturn(new RecoveryOutcome("OK", ""));

        assertThat(resolver.runOnce()).isEqualTo(1);

        verify(store).resolveCompletedAutomatically("Ev1", "2.2");
    }

    @Test
    void 답글이_없으면_결과_불명을_그대로_둔다_재발신_경로도_없다() {
        when(store.list()).thenReturn(List.of(entry("recovery", "Ev2", "UNKNOWN", 60_000)));
        when(threads.findReplies("C1", "1.1", "Ev2")).thenReturn(new ScanResult(List.of(), null, true));

        assertThat(resolver.runOnce()).isZero();

        verify(store, never()).resolveCompletedAutomatically(anyString(), anyString());
        verify(store, never()).resolveCompleted(anyString(), anyString());
        verify(store, never()).reprocess(anyString());
        verify(store, never()).close(anyString());
    }

    @Test
    void 조회가_실패하거나_끝까지_읽지_못해도_없다고_단정하지_않는다() {
        when(store.list()).thenReturn(List.of(entry("recovery", "Ev3", "UNKNOWN", 60_000)));
        when(threads.findReplies("C1", "1.1", "Ev3")).thenReturn(new ScanResult(List.of(), "missing_scope", false));

        assertThat(resolver.runOnce()).isZero();

        verify(store, never()).resolveCompletedAutomatically(anyString(), anyString());
        verify(store, never()).resolveCompleted(anyString(), anyString());
    }

    @Test
    void 방금_불명이_된_건은_조회에_반영되기_전일_수_있어_기다린다() {
        when(store.list()).thenReturn(List.of(entry("recovery", "Ev4", "UNKNOWN", 1_000)));

        assertThat(resolver.runOnce()).isZero();

        verify(threads, never()).findReplies(anyString(), anyString(), anyString());
    }

    @Test
    void DLQ와_UNKNOWN이_아닌_건은_조회하지_않는다() {
        when(store.list()).thenReturn(List.of(entry("dlq", "Ev5", "DEAD", 60_000),
                entry("recovery", "Ev6", "COMPLETED", 60_000)));

        assertThat(resolver.runOnce()).isZero();

        verify(threads, never()).findReplies(anyString(), anyString(), anyString());
    }

    @Test
    void 한_사이클의_예외가_밖으로_나가지_않는다() {
        when(store.list()).thenThrow(new IllegalStateException("저장소 다운"));

        assertThat(resolver.runOnce()).isZero();
    }

    @Test
    void 한_건의_예외가_뒤의_건을_막지_않는다() {
        when(store.list()).thenReturn(List.of(entry("recovery", "Bad", "UNKNOWN", 60_000),
                entry("recovery", "Good", "UNKNOWN", 60_000)));
        when(threads.findReplies("C1", "1.1", "Bad")).thenThrow(new IllegalStateException("조회 오류"));
        when(threads.findReplies("C1", "1.1", "Good"))
                .thenReturn(new ScanResult(List.of(new ReplyMatch("3.3", "att")), null, true));
        when(store.resolveCompletedAutomatically("Good", "3.3")).thenReturn(new RecoveryOutcome("OK", ""));

        assertThat(resolver.runOnce()).isEqualTo(1);
    }

    @Test
    void 한_사이클에_조회하는_건수에_상한이_있다() {
        List<Entry> many = new java.util.ArrayList<>();
        for (int i = 0; i < 25; i++) {
            many.add(entry("recovery", "E" + i, "UNKNOWN", 60_000));
        }
        when(store.list()).thenReturn(many);
        when(threads.findReplies(anyString(), anyString(), anyString())).thenReturn(new ScanResult(List.of(), null, true));

        resolver.runOnce();

        verify(threads, org.mockito.Mockito.times(10)).findReplies(anyString(), anyString(), anyString());
    }
}
