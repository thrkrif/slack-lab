package com.slack.lab.llm;

/** OpenAI 호환 {@code messages} 한 항목(AGENTS.md 규칙 3). 시스템 프롬프트는 클라이언트가 앞에 붙인다. */
public record LlmMessage(String role, String content) {

    public static LlmMessage user(String content) {
        return new LlmMessage("user", content);
    }

    public static LlmMessage assistant(String content) {
        return new LlmMessage("assistant", content);
    }
}
