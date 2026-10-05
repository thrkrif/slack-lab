package com.slack.lab.core.model;

import java.util.List;

/**
 * 한 질문에 대한 RAG 검색 결과. "검색은 성공했지만 쓸 문서가 없음"({@link NoRelevant})과 "검색을 못 함"({@link Unavailable})을
 * 일부러 나눈다 — 앞은 관련 문서가 없다는 정보이고 뒤는 장애라서 사용자에게 다른 안내가 나가야 한다. 어느 쪽도 처리 실패나
 * 재시도 사유가 아니다: RAG 없이 답하는 폴백이다.
 */
public sealed interface Retrieval {

    /** @param hits 프롬프트에 주입할 문서 조각(유사도 내림차순, 임계값·문맥 상한을 이미 적용). 비어 있지 않다 */
    record Found(List<DocumentHit> hits, long elapsedMs) implements Retrieval {
        public Found {
            hits = List.copyOf(hits);
        }
    }

    record NoRelevant(long elapsedMs) implements Retrieval {}

    record Unavailable(ErrorInfo error, long elapsedMs) implements Retrieval {}
}
