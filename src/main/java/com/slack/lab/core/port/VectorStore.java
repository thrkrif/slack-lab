package com.slack.lab.core.port;

import java.util.List;

/**
 * 과거 장애 기록의 벡터 저장·검색(3단계 RAG). 아직 구현체가 없다. RAG를 끄면 이 포트는 사용되지 않고,
 * 켜면 중복 억제와 같은 Postgres(pgvector)가 구현체가 된다(ADR-9).
 */
public interface VectorStore {

    /** 검색된 과거 문서 한 건. {@code source}는 답변에 붙일 출처 링크다. */
    record Hit(String documentId, String text, String source, double score) {}

    void upsert(String documentId, String text, String source, float[] embedding);

    List<Hit> search(float[] query, int topK);
}
