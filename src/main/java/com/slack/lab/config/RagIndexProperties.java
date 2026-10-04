package com.slack.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG 색인 CLI(INDEXER) 설정. 문서 경로는 저장소 <b>밖</b>이어야 한다 — 공개 저장소에 실제 장애 문서가 커밋되면 이력에
 * 남는다. 값 검증은 {@code IndexingService.Config}가 색인 역할에서만 한다.
 */
@ConfigurationProperties("rag.index")
public record RagIndexProperties(
        @DefaultValue("") String docsDir,
        @DefaultValue("800") int chunkSize,
        @DefaultValue("100") int chunkOverlap,
        @DefaultValue("0.5") double maxDeleteRatio,
        @DefaultValue("30000") long embedTimeoutMs,
        @DefaultValue("2") int embedAttempts,
        // 컨텍스트만 띄워 보는 테스트가 JVM을 죽이지 않게 끌 수 있다. 운영(CLI) 기본값은 항상 종료다.
        @DefaultValue("true") boolean exitAfterRun) {
}
