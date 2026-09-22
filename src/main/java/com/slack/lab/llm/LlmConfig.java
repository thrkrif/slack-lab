package com.slack.lab.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LlmConfig {

    private static final Logger log = LoggerFactory.getLogger(LlmConfig.class);

    @Bean
    public LlmClient llmClient(LlmProperties props, ObjectMapper mapper) {
        return switch (props.client()) {
            case OLLAMA -> new OpenAiCompatibleLlmClient(props, mapper);
            case ECHO -> new EchoLlmClient();
        };
    }

    /**
     * 기동 시 모델 존재를 fail-fast로 확인하고(pitfall 8) 1회 웜업한다. 우회는 {@code llm.verify-model-on-startup=false}
     * (A6 유도용, PLAN §3 M4). Echo 클라이언트는 검증·웜업 대상이 아니다.
     */
    @Bean
    public ApplicationRunner llmStartupCheck(LlmClient client, LlmProperties props) {
        return (ApplicationArguments args) -> {
            if (!(client instanceof OpenAiCompatibleLlmClient openAiClient)) {
                return;
            }
            if (props.verifyModelOnStartup()) {
                openAiClient.verifyModelExists();
                log.info("LLM 모델 확인됨 model={}", props.model());
            } else {
                log.warn("llm.verify-model-on-startup=false — 모델 확인을 건너뜀");
            }
            openAiClient.warmUp();
        };
    }
}
