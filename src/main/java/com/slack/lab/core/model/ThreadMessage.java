package com.slack.lab.core.model;

/** 스레드 메시지 하나. 문맥 조립에 필요한 필드만 담는다. */
public record ThreadMessage(String ts, String user, String botId, String text) {}
