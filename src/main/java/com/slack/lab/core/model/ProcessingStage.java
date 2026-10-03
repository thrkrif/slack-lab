package com.slack.lab.core.model;

/**
 * 한 건이 어디까지 갔는지(또는 어떻게 끝났는지)를 나타내는 처리 단계. 실패 원인은 {@link ErrorCode}가 말한다.
 * {@link #code()}는 DB·로그에 남는 고정 값이다.
 */
public enum ProcessingStage {
    /** LLM 호출(문맥 조회 포함). */
    LLM("llm"),
    /** 채널 발신. */
    SEND("send"),
    /** 발신 이전의 그 밖의 처리. */
    PROCESSING("processing"),
    DELIVERED("delivered"),
    /** 저장소가 스스로 종료·격리한 건(임대 만료, 창 만료, 깨진 입력 등). */
    STATE("state"),
    RESOLVED_AUTO("resolved_auto"),
    RESOLVED_MANUAL("resolved_manual"),
    CLOSED_MANUAL("closed_manual"),
    REPROCESS_MANUAL("reprocess_manual");

    private final String code;

    ProcessingStage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
