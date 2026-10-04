package com.slack.lab.core.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 답변에 붙일 "참고 문서" 목록(문서 ID·제목)의 의미 모델. Slack 서식과 특수문자 이스케이프는 Slack 지식이므로
 * 코어가 아니라 채팅 어댑터가 렌더링한다. 실제로 프롬프트에 주입한 청크의 문서만 담고, 같은 문서는 한 번만 나온다.
 */
public record ReferenceList(List<Reference> references) {

    public record Reference(String documentId, String title) {}

    public ReferenceList {
        references = List.copyOf(references);
    }

    public static ReferenceList empty() {
        return new ReferenceList(List.of());
    }

    /** 주입한 순서(유사도 순)를 유지하며 문서 ID 기준으로 중복을 제거한다. */
    public static ReferenceList fromInjected(List<DocumentHit> injected) {
        var byId = new LinkedHashMap<String, Reference>();
        for (DocumentHit hit : injected) {
            byId.putIfAbsent(hit.documentId(), new Reference(hit.documentId(), hit.title()));
        }
        return new ReferenceList(new ArrayList<>(byId.values()));
    }

    /** 주입 0건이면 출처 줄 자체를 생략한다. */
    public boolean isEmpty() {
        return references.isEmpty();
    }
}
