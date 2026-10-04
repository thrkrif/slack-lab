package com.slack.lab.adapter.embedding;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.RagProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 임베딩 클라이언트 배선. {@code rag.enabled=true}일 때만 만들고, 끄면 흔적도 남지 않는다(꺼도 동작). 기동 확인은 {@code RagStartupCheck}. */
@Configuration
@ConditionalOnRole({AppRole.WORKER, AppRole.INDEXER, AppRole.ALL})
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
class EmbeddingConfig {

    @Bean(destroyMethod = "close")
    OpenAiCompatibleEmbeddingClient embeddingClient(RagProperties props, ObjectMapper mapper) {
        return new OpenAiCompatibleEmbeddingClient(props, mapper);
    }
}
