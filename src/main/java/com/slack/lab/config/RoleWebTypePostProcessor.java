package com.slack.lab.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * 웹 서버가 필요 없는 역할(워커·반응·복구)은 포트를 열지 않는다. 수신 서버만 ngrok에 노출되게 하고,
 * 복구 CLI가 실수로 공개 엔드포인트가 되지 않게 하려는 것이다(PLAN 2단계 리스크 표).
 */
public class RoleWebTypePostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        if (!OnRoleCondition.currentRole(env).servesHttp()) {
            env.getPropertySources().addFirst(new MapPropertySource("appRoleWebType",
                    Map.of("spring.main.web-application-type", "none")));
        }
    }
}
