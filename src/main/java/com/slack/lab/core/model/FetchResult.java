package com.slack.lab.core.model;

import java.util.List;

/** 스레드 메시지 조회 결과. {@code complete}가 false면 끝까지 읽지 못한 것이다. */
public record FetchResult(List<ThreadMessage> messages, String error, boolean complete) {
    public boolean failed() {
        return error != null;
    }
}
