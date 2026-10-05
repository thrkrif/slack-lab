package com.slack.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * RAG 검색(질의 시점) 설정. 기본값은 M30 실측으로 정했다(EXPERIMENT-LOG §31): 임계값은 tuning 세트로만 조정했고(final은
 * 보지 않았다), 문맥 상한은 Ollama 4K 컨텍스트에서 스레드 문맥과 함께 잘리지 않도록 측정해 낮췄다. 모델·코퍼스가 바뀌면 같은
 * 절차로 다시 정한다(점수 분포는 임베딩 모델마다 다르다). 검색 상한 시간은 {@link RagProperties#searchDeadlineMs()}.
 */
@ConfigurationProperties("rag.retrieval")
public record RagRetrievalProperties(
        @DefaultValue("3") int topK,
        // 코사인 유사도 하한(bge-m3 기준). tuning 세트에서 정답 문서의 최고 점수는 0.55~0.77, 정답 없는 질문의 최고 점수는 0.50~0.52였다.
        // 정답 없는 질문이 2개뿐이라 근거가 약하다 — 다른 임베딩 모델이면 분포가 다르니(nomic은 분리 불가) 다시 정한다.
        @DefaultValue("0.54") double minScore,
        // 주입하는 조각 글자 수의 합. 시스템 프롬프트 + 스레드 문맥 상한(4000자) + 이 값 + 질문 + 답변(512토큰)이 Ollama 기본 4K
        // 컨텍스트 안에 들어야 한다 — 넘으면 Ollama가 조용히 앞부분을 잘라낸다(실측: 9000자 입력이 2050토큰으로 보고됨).
        @DefaultValue("1500") int maxContextChars,
        @DefaultValue("500") int maxQueryChars) {
}
