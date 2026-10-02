package com.slack.lab.adapter.redis;

import com.slack.lab.core.model.SlackMessageEvent;
import com.slack.lab.core.port.QueueDelivery;
import java.util.Map;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis Streams 항목 하나({@code XREADGROUP}으로 받은 것)를 코어의 {@link QueueDelivery}로 감싼다. */
final class RedisDelivery implements QueueDelivery {

    private final StringRedisTemplate redis;
    private final String streamKey;
    private final String group;
    private final String streamId;
    private final SlackMessageEvent event;
    private final long gen;
    private final long receivedAtMs;
    private final boolean receivedAtMissing;

    private RedisDelivery(StringRedisTemplate redis, String streamKey, String group, String streamId,
            SlackMessageEvent event, long gen, long receivedAtMs, boolean receivedAtMissing) {
        this.redis = redis;
        this.streamKey = streamKey;
        this.group = group;
        this.streamId = streamId;
        this.event = event;
        this.gen = gen;
        this.receivedAtMs = receivedAtMs;
        this.receivedAtMissing = receivedAtMissing;
    }

    /** event_id가 없는 항목은 만들 수 없다(null). */
    static RedisDelivery of(StringRedisTemplate redis, String streamKey, String group, String streamId,
            Map<Object, Object> fields) {
        SlackMessageEvent event = fromQueueFields(fields);
        if (event.eventId() == null) {
            return null;
        }
        // received_at이 없거나 깨졌으면 지금으로 대체해 구간이 0이 되는데, 이를 정상 값으로 두면 측정이 조용히
        // 틀어진다 — 표시해 두었다가 지표 줄에서 무효로 취급한다.
        long parsed = parseLongOr(str(fields, "received_at"), -1);
        boolean missing = parsed < 0;
        return new RedisDelivery(redis, streamKey, group, streamId, event, parseLongOr(str(fields, "gen"), 0),
                missing ? System.currentTimeMillis() : parsed, missing);
    }

    /**
     * 큐 메시지 필드에서 재구성한다(M12). 발행자는 {@code bot_id}·{@code subtype}·봇 user_id를 싣지 않는다 —
     * 필터링은 발행 전 수신 서버에서 이미 끝났고, 봇 멘션 제거는 {@code promptText()}의 선행 멘션 정리(fallback)로
     * 충분하기 때문이다(PLAN 2단계 M12).
     */
    static SlackMessageEvent fromQueueFields(Map<Object, Object> fields) {
        return new SlackMessageEvent(
                str(fields, "event_id"), str(fields, "channel"), str(fields, "user"), str(fields, "text"),
                str(fields, "ts"), str(fields, "thread_ts"), null, null, null);
    }

    @Override
    public SlackMessageEvent event() {
        return event;
    }

    @Override
    public long gen() {
        return gen;
    }

    @Override
    public long receivedAtMs() {
        return receivedAtMs;
    }

    @Override
    public boolean receivedAtMissing() {
        return receivedAtMissing;
    }

    @Override
    public String token() {
        return streamId;
    }

    @Override
    public void acknowledge() {
        RedisStreams.ackDel(redis, streamKey, group, streamId);
    }

    @Override
    public void defer() {
        // pending에 그대로 남는다. 회수 주기(claim-min-idle 이후)가 다시 꺼내 선점 결과표로 판정한다.
    }

    private static String str(Map<Object, Object> fields, String key) {
        Object v = fields.get(key);
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isEmpty() ? null : s;
    }

    private static long parseLongOr(String s, long fallback) {
        if (s == null) {
            return fallback;
        }
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
