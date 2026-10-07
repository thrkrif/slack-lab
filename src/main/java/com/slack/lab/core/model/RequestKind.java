package com.slack.lab.core.model;

/** 멘션 질문의 종류(4단계 P2-5). 정의와 경계는 {@code docs/rag-eval/classification-labels.md}가 정본이다. */
public enum RequestKind {
    /** 장애 질문 — 검색 후 답변(3단계 흐름 그대로). 분류가 실패하면 이쪽으로 폴백한다. */
    TROUBLE,
    /** 단순 질문 — 검색 없이 답변. */
    SIMPLE,
    /** 정보 부족 — 검색 없이 되묻는다. */
    NEEDS_INFO
}
