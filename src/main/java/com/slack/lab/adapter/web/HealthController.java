package com.slack.lab.adapter.web;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.core.port.HealthProbe;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 의존 시스템 중 하나라도 죽으면 수신 서버는 이벤트를 수락할 수 없다 — "죽으면 죽은 줄 안다"(PRD §5 가용성)를 503으로
 * 드러낸다. 무엇을 검사하는지는 {@link HealthProbe} 구현체(어댑터)가 정한다.
 */
@RestController
@ConditionalOnRole({AppRole.RECEIVER, AppRole.ALL})
public class HealthController {

    private final List<HealthProbe> probes;

    public HealthController(List<HealthProbe> probes) {
        this.probes = probes;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        Map<String, String> body = new LinkedHashMap<>();
        boolean allUp = true;
        for (HealthProbe probe : probes) {
            boolean up = probe.up();
            allUp &= up;
            body.put(probe.name(), up ? "UP" : "DOWN");
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put("status", allUp ? "UP" : "DOWN");
        out.putAll(body);
        return ResponseEntity.status(allUp ? 200 : 503).body(out);
    }
}
