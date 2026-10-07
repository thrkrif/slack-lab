package com.slack.lab.core.port;

import com.slack.lab.core.model.ClassifyResult;

/** 멘션 질문을 종류(장애·단순·정보 부족)로 가른다. 구현체는 모델·프롬프트를 스스로 안다 — 코어는 결과만 쓴다. */
public interface RequestClassifier {

    /**
     * @param question    봇 멘션 토큰이 제거된 질문 텍스트
     * @param remainingMs 이 호출에 쓸 수 있는 남은 시간. 구현체가 자체 상한과 비교해 더 작은 쪽을 마감으로 쓴다
     * @return 종류 또는 실패. 예외를 던지지 않는다
     */
    ClassifyResult classify(String question, long remainingMs);
}
