package com.slack.lab.config;

import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.VectorStore;
import com.slack.lab.core.service.RetrievalService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 워커가 RAG를 켰을 때만 검색 서비스를 만든다. 없으면 핸들러는 기존 흐름 그대로 간다(꺼도 동작). */
@Configuration
@ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
class RagRetrievalConfig {

    @Bean
    RetrievalService retrievalService(EmbeddingClient embedding, VectorStore store, RagProperties rag,
            RagRetrievalProperties retrieval) {
        return new RetrievalService(embedding, store, new RetrievalService.Config(rag.searchDeadlineMs(), retrieval.topK(),
                retrieval.minScore(), retrieval.maxContextChars(), retrieval.maxQueryChars()));
    }
}
