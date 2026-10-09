package com.slack.lab.core.model;

import java.util.regex.Pattern;

/** 처리에 필요한 값만 뽑은 이벤트 값 객체. HTTP·JSON·브로커 타입을 모른다(파싱은 각 어댑터의 몫). */
public record SlackMessageEvent(
        String eventId,
        String channel,
        String user,
        String text,
        String ts,
        String threadTs,
        String botId,
        String subtype,
        String botUserId) {

    private static final Pattern MENTION = Pattern.compile("<@([A-Z0-9]+)(\\|[^>]*)?>");
    private static final Pattern LEADING_MENTIONS = Pattern.compile("^(\\s*<@[A-Z0-9]+(\\|[^>]*)?>)+");

    /** 봇 메시지·subtype이 있는 이벤트는 처리하지 않는다. 걸러내지 않으면 봇 답글이 다시 들어와 무한 루프가 된다. */
    public boolean shouldIgnore() {
        return botId != null || subtype != null;
    }

    /**
     * 알람 경로가 만든 이벤트인가. 알람은 Slack 메시지가 아니라서 {@code ts}·{@code threadTs}가 없다({@code AlertEvent}가 그렇게
     * 만든다). 이 규칙을 한 곳에 둬서 호출자가 {@code ts == null}을 각자 해석하지 않게 한다.
     */
    public boolean isAlert() {
        return ts == null && threadTs == null;
    }

    /** 스레드 안 멘션이면 원 스레드에, 아니면 원 메시지 아래에 답한다. */
    public String replyThreadTs() {
        return threadTs != null ? threadTs : ts;
    }

    /**
     * LLM에 넘길 텍스트. 봇 ID를 알면 봇 멘션만 지우고(다른 사람 멘션은 질문의 일부일 수 있다),
     * 모르면 문장 앞의 멘션만 지운다.
     */
    public String promptText() {
        return cleanText(text, botUserId);
    }

    /** 스레드 문맥의 이전 메시지도 같은 규칙으로 정리한다(M16). */
    public static String cleanText(String text, String botUserId) {
        String t = text == null ? "" : text;
        if (botUserId != null) {
            t = MENTION.matcher(t).replaceAll(m -> m.group(1).equals(botUserId) ? "" : java.util.regex.Matcher.quoteReplacement(m.group()));
        } else {
            t = LEADING_MENTIONS.matcher(t).replaceFirst("");
        }
        return t.trim().replaceAll("\\s+", " ");
    }
}
