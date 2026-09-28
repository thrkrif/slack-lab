package com.slack.lab.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;

class OnRoleCondition implements Condition {

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        Map<String, Object> attrs = metadata.getAnnotationAttributes(ConditionalOnRole.class.getName());
        AppRole[] allowed = (AppRole[]) attrs.get("value");
        return Arrays.asList(allowed).contains(currentRole(context.getEnvironment()));
    }

    // 빈 조건 평가는 @ConfigurationProperties 바인딩보다 먼저라 환경에서 직접 읽는다.
    static AppRole currentRole(Environment env) {
        String raw = env.getProperty("app.role", "all").trim();
        return AppRole.valueOf(raw.toUpperCase(Locale.ROOT).replace('-', '_'));
    }
}
