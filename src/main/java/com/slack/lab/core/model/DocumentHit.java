package com.slack.lab.core.model;

/**
 * 검색된 청크 한 건. 파일 경로·URL 같은 출처 위치는 일부러 담지 않는다 — 답변에 노출되면 안 되는 값이고(PRD 3단계 범위),
 * 표시에 필요한 것은 문서 ID와 제목뿐이다.
 *
 * @param score 코사인 유사도(−1.0~1.0, 클수록 가깝다). 저장소가 거리(예: pgvector {@code <=>})를 쓰면 어댑터가
 *     {@code 1 - distance}로 바꿔서 돌려준다 — 임계값이 저장소 구현에 묶이지 않게 한다
 */
public record DocumentHit(String documentId, String title, String text, double score) {}
