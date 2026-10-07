package com.slack.lab.adapter.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ClassificationProperties;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.LlmProperties;
import com.slack.lab.core.port.RequestClassifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 분류를 켠 워커·평가 CLI에서만 분류기를 만든다. 없으면 핸들러는 3단계 흐름 그대로 간다(꺼도 동작). */
@Configuration
@ConditionalOnRole({AppRole.WORKER, AppRole.EVALUATOR, AppRole.ALL})
@ConditionalOnProperty(prefix = "classification", name = "enabled", havingValue = "true")
class ClassificationConfig {

    @Bean
    RequestClassifier requestClassifier(ClassificationProperties props, LlmProperties llm, ObjectMapper mapper) {
        return new OpenAiCompatibleRequestClassifier(props, llm, mapper);
    }
}
