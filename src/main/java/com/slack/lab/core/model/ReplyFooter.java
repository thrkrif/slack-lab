package com.slack.lab.core.model;

/**
 * 답변 본문 뒤에 서버가 붙이는 덧붙임의 <b>의미</b>. 모델이 쓰는 값이 아니라 서버가 정하므로 형식 검사가 안정적이다. 문구와
 * 서식, 채팅 서비스별 특수문자 이스케이프는 {@code ChatNotifier} 구현체의 몫이다(코어는 Slack 서식을 모른다).
 */
public sealed interface ReplyFooter {

    /** 덧붙임 없음. 답변이 아닌 메시지(실패 안내)와 RAG를 끈 경우다. */
    record None() implements ReplyFooter {}

    /** 프롬프트에 실제로 주입한 문서 목록. 모델이 그 문서를 근거로 썼다는 검증은 아니다. */
    record References(ReferenceList references) implements ReplyFooter {}

    /** 검색은 성공했지만 쓸 문서가 없어 일반 지식으로 답했다. */
    record NoRelevantDocuments() implements ReplyFooter {}

    /** 검색을 하지 못해(오류·기한 초과) 일반 지식으로 답했다. */
    record SearchUnavailable() implements ReplyFooter {}

    ReplyFooter NONE = new None();

    /**
     * 채팅 어댑터가 덧붙임을 렌더링할 때 쓰는 표지. 스레드 문맥을 만들 때 이전 봇 답글에서 덧붙임을 걷어내려고 코어도 알아야
     * 한다 — 걷어내지 않으면 모델이 "참고 문서" 서식을 흉내 내 본문에 가짜 출처를 쓸 수 있다.
     */
    String REFERENCES_HEADER = "*참고 문서*";
    String NO_RELEVANT_TEXT = "_관련 문서 근거를 찾지 못해 일반 지식으로 답변했습니다._";
    String SEARCH_UNAVAILABLE_TEXT = "_문서 검색을 완료하지 못해 일반 지식으로 답변합니다. 내부 운영 절차와 다를 수 있습니다._";

    /** 렌더링된 봇 답글에서 서버가 붙인 덧붙임(참고 문서 목록·안내 문구)을 뺀 본문. 덧붙임이 없으면 그대로다. */
    static String stripFooter(String text) {
        int refs = text.lastIndexOf("\n\n" + REFERENCES_HEADER);
        if (refs >= 0) {
            return text.substring(0, refs);
        }
        for (String notice : new String[] {NO_RELEVANT_TEXT, SEARCH_UNAVAILABLE_TEXT}) {
            String tail = "\n\n" + notice;
            if (text.endsWith(tail)) {
                return text.substring(0, text.length() - tail.length());
            }
        }
        return text;
    }

    default boolean isNone() {
        return this instanceof None;
    }
}
