package com.slack.lab.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * 3단계 RAG 설정(ADR-9 스위치 둘). {@code enabled=false}(기본)이면 임베딩·벡터 저장소 빈을 만들지 않고 아래 검사도 하지
 * 않는다 — 기존에 LLM을 외부로 쓰던 사용자가 RAG를 안 켜면 영향이 없어야 한다.
 *
 * <p>모델 ID와 차원은 기본값이 없다(추측 금지, {@code ollama list}로 확인). 차원은 색인 메타와 맞아야 하고 어긋나면 기동을
 * 거부한다. 유출 경로는 벡터 저장소가 아니라 LLM·임베딩 호출이므로, 호스트가 {@code allowedHosts} 밖이면 해당 허용
 * 플래그를 명시하지 않는 한 기동을 거부한다(임베딩은 문서 원문이, LLM은 검색된 조각과 질문이 나간다 — 그래서 플래그가 둘이다).
 *
 * <p>값 검증은 빈 검증 어노테이션이 아니라 {@link RagGuard}가 <b>켰을 때만</b> 한다 — 꺼 둔 사용자의 설정이 잘못돼도(빈 URL 등)
 * 기존 앱이 기동에 실패하면 안 된다.
 */
@ConfigurationProperties("rag")
public record RagProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("http://localhost:11434/v1") String embeddingBaseUrl,
        @DefaultValue("") String embeddingModel,
        @DefaultValue("0") int embeddingDimension,
        // 검색(질의 임베딩 + 벡터 조회) 상한. 50초 LLM 예산 안에서 쓰고 넘으면 RAG 없이 답한다(PLAN M28).
        @DefaultValue("5000") long searchDeadlineMs,
        @DefaultValue("3000") long connectTimeoutMs,
        @DefaultValue("true") boolean verifyOnStartup,
        @DefaultValue("false") boolean allowExternalLlm,
        @DefaultValue("false") boolean allowExternalEmbedding,
        // 정확한 호스트 이름, "*.corp.internal" 꼴 접미사, IPv4 CIDR("10.0.0.0/8")을 쓴다. 기본은 이 기기 안뿐이다.
        @DefaultValue({"localhost", "127.0.0.1", "::1", "host.docker.internal"}) List<String> allowedHosts) {
}
