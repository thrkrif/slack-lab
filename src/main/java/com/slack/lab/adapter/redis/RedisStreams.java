package com.slack.lab.adapter.redis;

import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/** Redis Streams 공통 도구. */
final class RedisStreams {

    // ACK와 본문 삭제를 한 명령으로 — ACK만 하면 스트림에 항목이 쌓이고, XACK 뒤 XDEL 전에 끊기면 본문이 pending 밖에
    // 남는다(ARCHITECTURE ADR-8). 이미 지워진 항목에는 아무 일도 하지 않으므로 멱등이다.
    private static final RedisScript<Long> ACKDEL = new DefaultRedisScript<>(
            "return redis.call('XACKDEL', KEYS[1], ARGV[1], 'IDS', 1, ARGV[2])", Long.class);

    private RedisStreams() {}

    static void ackDel(StringRedisTemplate redis, String streamKey, String group, String streamId) {
        redis.execute(ACKDEL, List.of(streamKey), group, streamId);
    }
}
