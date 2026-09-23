package com.slack.lab.event;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.regex.Pattern;

/** event_callback 페이로드에서 처리에 필요한 값만 뽑은 값 객체. HTTP 타입을 모른다. */
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

    /** 필수 값(event_id·channel·ts)이 없으면 IllegalArgumentException. 호출자가 400으로 바꾼다. */
    public static SlackMessageEvent from(JsonNode root) {
        JsonNode ev = root.path("event");
        return new SlackMessageEvent(
                required(root, "event_id"),
                required(ev, "channel"),
                text(ev, "user"),
                text(ev, "text"),
                required(ev, "ts"),
                text(ev, "thread_ts"),
                text(ev, "bot_id"),
                text(ev, "subtype"),
                // 어떤 멘션 토큰이 봇 자신인지 알려 주는 값. 페이로드에 없으면 null이다.
                text(root.path("authorizations").path(0), "user_id"));
    }

    /** 봇 메시지·subtype이 있는 이벤트는 처리하지 않는다. 걸러내지 않으면 봇 답글이 다시 들어와 무한 루프가 된다. */
    public boolean shouldIgnore() {
        return botId != null || subtype != null;
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
        String t = text == null ? "" : text;
        if (botUserId != null) {
            t = MENTION.matcher(t).replaceAll(m -> m.group(1).equals(botUserId) ? "" : java.util.regex.Matcher.quoteReplacement(m.group()));
        } else {
            t = LEADING_MENTIONS.matcher(t).replaceFirst("");
        }
        return t.trim().replaceAll("\\s+", " ");
    }

    private static String required(JsonNode node, String field) {
        String v = text(node, field);
        if (v == null) {
            throw new IllegalArgumentException("필수 필드 누락: " + field);
        }
        return v;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isTextual() && !v.asText().isEmpty() ? v.asText() : null;
    }
}
