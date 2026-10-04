package com.slack.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG 검색(질의 시점) 설정. 기본값은 초기값이며 K·임계값·문맥 상한은 M30에서 튜닝 세트로 조정한다(최종 평가 질문으로
 * 조정하지 않는다). 검색 상한 시간은 {@link RagProperties#searchDeadlineMs()}.
 */
@ConfigurationProperties("rag.retrieval")
public record RagRetrievalProperties(
        @DefaultValue("3") int topK,
        // 코사인 유사도 하한. 정답 없는 질문에 무관한 문서를 억지로 끼우지 않기 위한 값이다.
        @DefaultValue("0.5") double minScore,
        // 주입하는 조각 글자 수의 합. 문맥 4K에서 질문·스레드·답변 몫을 남기는 값이다.
        @DefaultValue("3000") int maxContextChars,
        @DefaultValue("500") int maxQueryChars) {
}
