package com.slack.lab.core.model;

/** 스레드에서 찾은 우리 봇 답글 하나(메타데이터가 일치한 것). */
public record ReplyMatch(String ts, String attemptId) {}
