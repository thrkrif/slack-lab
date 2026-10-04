package com.slack.lab.adapter.docs;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.RagProperties;
import com.slack.lab.config.RagIndexProperties;
import com.slack.lab.core.port.DocumentSource;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.IndexLock;
import com.slack.lab.core.port.VectorStore;
import com.slack.lab.core.service.IndexingService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 색인 CLI(INDEXER) 배선: 로컬 마크다운 출처와 코어 {@link IndexingService}. */
@Configuration
@ConditionalOnRole(AppRole.INDEXER)
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
class DocsConfig {

    @Bean
    DocumentSource documentSource(RagIndexProperties index) {
        if (index.docsDir().isBlank()) {
            throw new IllegalStateException("rag.index.docs-dir(RAG_DOCS_DIR)가 필요하다. 저장소 밖 문서 디렉터리를 가리킨다");
        }
        return new LocalMarkdownDocumentSource(index.docsDir());
    }

    @Bean
    IndexingService indexingService(DocumentSource source, EmbeddingClient embedding, VectorStore store, IndexLock lock,
            RagProperties rag, RagIndexProperties index) {
        return new IndexingService(source, embedding, store, lock, new IndexingService.Config(rag.embeddingModel(),
                rag.embeddingDimension(), index.chunkSize(), index.chunkOverlap(), index.maxDeleteRatio(),
                index.embedTimeoutMs(), index.embedAttempts()));
    }
}
