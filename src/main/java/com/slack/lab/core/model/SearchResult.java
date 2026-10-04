package com.slack.lab.core.model;

import java.util.List;

/** 벡터 검색 결과. 기한 초과·실패는 호출자가 RAG 없이 진행하도록 타입으로 드러낸다(PLAN M28). */
public sealed interface SearchResult {

    /** @param hits 유사도 내림차순. 비어 있으면 "검색은 성공했지만 관련 문서 없음"이다(장애와 구분) */
    record Success(List<DocumentHit> hits, long elapsedMs) implements SearchResult {
        public Success {
            hits = List.copyOf(hits);
        }
    }

    record TimedOut(long elapsedMs) implements SearchResult {}

    record Failed(ErrorInfo error, long elapsedMs) implements SearchResult {}
}
