package com.slack.lab.core.model;

import java.util.List;

/**
 * RAG 평가 질문 한 건. {@code promptText}는 운영 경로가 {@code SlackEventHandler}에서 받는 그 텍스트다(알람형은 고정 안내문 +
 * 알람 블록, 멘션형은 질문 그대로) — 평가가 운영과 같은 질의 추출을 거치게 하려는 것이다.
 *
 * @param set {@code final}(합격 판정) 또는 {@code tuning}(K·청크 크기·임계값 조정용, 판정에 쓰지 않는다). 두 세트의 질문은
 *     겹치지 않는다 — 조정에 쓴 질문으로 최종 판정을 하면 기준을 낮출 여지가 생긴다
 * @param expected 정답 문서 ID. 비어 있으면 "정답 문서가 없는 질문"이다(근거를 억지로 끼우지 않는지 본다)
 */
public record EvalQuestion(String id, String set, Kind kind, List<String> expected, String promptText) {

    public enum Kind {
        ALARM, MENTION
    }

    public static final String FINAL = "final";
    public static final String TUNING = "tuning";

    public EvalQuestion {
        expected = List.copyOf(expected);
    }

    public boolean answerable() {
        return !expected.isEmpty();
    }
}
