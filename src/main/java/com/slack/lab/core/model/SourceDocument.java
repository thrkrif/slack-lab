package com.slack.lab.core.model;

/**
 * 문서 출처가 내놓는 문서 한 건. {@code id}는 출처 안에서 안정적이고 <b>공개해도 되는 불투명 식별자</b>다(예: 문서의
 * front matter {@code id}나 파일 이름 stem). 디렉터리 경로·절대 경로를 담으면 안 된다 — 답변의 "참고 문서"에 그대로
 * 나가기 때문이다. id가 바뀌면 다른 문서로 취급한다. 파일 시스템 위치와의 대응은 어댑터가 안에서만 쥔다.
 *
 * @param contentHash 내용 해시. 증분 색인 키의 한 요소다(나머지는 청킹 설정과 모델 ID·차원)
 */
public record SourceDocument(String id, String title, String content, String contentHash) {}
