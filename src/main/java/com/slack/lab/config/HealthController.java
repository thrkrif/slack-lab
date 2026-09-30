package com.slack.lab.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Redis가 죽으면 수신 서버는 이벤트를 수락할 수 없다 — "죽으면 죽은 줄 안다"(PRD §5 가용성)를 503으로 드러낸다. */
@RestController
public class HealthController {

    private final RedisConnectionFactory redis;

    public HealthController(RedisConnectionFactory redis) {
        this.redis = redis;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        String redisStatus = redisStatus();
        Map<String, String> body = new LinkedHashMap<>();
        body.put("status", "DOWN".equals(redisStatus) ? "DOWN" : "UP");
        body.put("redis", redisStatus);
        return ResponseEntity.status("DOWN".equals(redisStatus) ? 503 : 200).body(body);
    }

    private String redisStatus() {
        try (RedisConnection connection = redis.getConnection()) {
            return "PONG".equals(connection.ping()) ? "UP" : "DOWN";
        } catch (RuntimeException e) {
            return "DOWN";
        }
    }
}
