package com.slack.lab.adapter.slack;

import com.slack.lab.core.model.ReferenceList;
import com.slack.lab.core.model.ReplyFooter;

/**
 * {@link ReplyFooter}를 Slack mrkdwn 문구로 바꾼다. 문서 ID·제목은 외부 입력이라 Slack 특수문자를 이스케이프한다: {@code <}가
 * 남으면 {@code <!channel>}·{@code <@U…>}·링크로 해석돼 멘션 폭주나 피싱 링크가 될 수 있다. 로컬 경로와 문서 원문은 이 모델에
 * 들어 있지 않아 노출될 수 없다.
 */
final class SlackFooterRenderer {

    static final String NO_RELEVANT = ReplyFooter.NO_RELEVANT_TEXT;
    static final String SEARCH_UNAVAILABLE = ReplyFooter.SEARCH_UNAVAILABLE_TEXT;
    private static final int MAX_TITLE = 120;

    private SlackFooterRenderer() {}

    /**
     * 모델 답변 본문의 {@code <!channel>}·{@code <@U…>}·{@code <#C…>}는 Slack이 멘션으로 해석한다. RAG로 "색인 문서 → LLM →
     * 본문"이라는 주입 경로가 생겼으므로 {@code <!}·{@code <@}·{@code <#}만 엔티티로 바꿔 알림이 나가지 않게 한다(일반 링크
     * 서식은 건드리지 않는다).
     */
    static String sanitizeBody(String text) {
        return text.replace("<!", "&lt;!").replace("<@", "&lt;@").replace("<#", "&lt;#");
    }

    static String render(String text, ReplyFooter footer) {
        text = sanitizeBody(text);
        return switch (footer) {
            case ReplyFooter.None none -> text;
            case ReplyFooter.NoRelevantDocuments n -> text + "\n\n" + NO_RELEVANT;
            case ReplyFooter.SearchUnavailable u -> text + "\n\n" + SEARCH_UNAVAILABLE;
            case ReplyFooter.References r -> r.references().isEmpty() ? text : text + "\n\n" + references(r.references());
        };
    }

    private static String references(ReferenceList list) {
        StringBuilder sb = new StringBuilder(ReplyFooter.REFERENCES_HEADER);
        for (ReferenceList.Reference ref : list.references()) {
            sb.append("\n• [").append(clean(ref.documentId(), MAX_TITLE)).append("] ").append(clean(ref.title(), MAX_TITLE));
        }
        return sb.toString();
    }

    /** Slack이 해석하는 {@code & < >}를 엔티티로, 서식 문자와 제어 문자·줄바꿈은 제거해 한 줄의 평문으로 만든다. */
    static String clean(String s, int max) {
        String flat = s.replaceAll("[\\p{Cntrl}\\u2028\\u2029]+", " ").replaceAll("[*_~`]", "").strip();
        if (flat.length() > max) {
            flat = flat.substring(0, max) + "…";
        }
        return flat.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
