package com.slack.lab.core.model;

/** 채널로 내보내는 메시지의 종류. 오류와 단계에서 분리해 문자열 조합으로 복원하지 않는다. */
public enum MessageKind {
    ANSWER("answer"),
    FAILURE_NOTICE("failure_notice");

    private final String code;

    MessageKind(String code) {
        this.code = code;
    }

    /** DB·로그에 남는 고정 값. */
    public String code() {
        return code;
    }
}
