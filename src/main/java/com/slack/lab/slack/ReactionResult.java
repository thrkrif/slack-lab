package com.slack.lab.slack;

/** {@code reactions.add} 결과. {@code ok}는 반응이 붙어 있는 상태(새로 붙였거나 이미 붙어 있음)를 뜻한다. */
public record ReactionResult(boolean ok, String detail) {}
