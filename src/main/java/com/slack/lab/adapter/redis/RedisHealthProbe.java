package com.slack.lab.adapter.redis;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.core.port.HealthProbe;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.stereotype.Component;

/** Redis가 죽으면 수신 서버는 이벤트를 수락할 수 없다 — "죽으면 죽은 줄 안다"(PRD §5 가용성)를 드러낸다. */
@Component
@ConditionalOnRole({AppRole.RECEIVER, AppRole.ALL})
public class RedisHealthProbe implements HealthProbe {

    private final RedisConnectionFactory redis;

    public RedisHealthProbe(RedisConnectionFactory redis) {
        this.redis = redis;
    }

    @Override
    public String name() {
        return "redis";
    }

    @Override
    public boolean up() {
        try (RedisConnection connection = redis.getConnection()) {
            return "PONG".equals(connection.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }
}
