package com.slack.lab.core.model;

/**
 * 분류 평가 질문 한 건. {@code set}은 {@code final}(합격 판정, 튜닝 금지) 또는 {@code tuning}(프롬프트 조정용, 판정에 쓰지 않는다)이다.
 */
public record ClassifyQuestion(String id, String set, RequestKind expected, String text) {

    public static final String FINAL = "final";
    public static final String TUNING = "tuning";
}
