package com.slack.lab.event;

import com.slack.lab.llm.LlmMessage;
import java.util.List;

/**
 * 스레드 안 멘션의 이전 대화를 LLM 문맥으로 가져온다(M16). 핸들러는 HTTP를 모르므로(규칙 2) 조회 방법은 이 인터페이스 뒤에
 * 둔다. 구현체는 예외를 던지지 않는다 — 실패·시간 초과는 빈 목록이고 호출자는 문맥 없이 진행한다.
 */
public interface ThreadContextSource {

    /** 문맥 없이 진행하는 구현. */
    ThreadContextSource NONE = (event, budgetMs) -> List.of();

    /**
     * @param budgetMs 이 조회에 쓸 수 있는 최대 시간. 구현체는 자체 상한과 비교해 더 짧은 쪽을 쓴다
     * @return 오래된 것부터 정렬된 user/assistant 메시지. 이번 메시지는 포함하지 않는다
     */
    List<LlmMessage> fetch(SlackMessageEvent event, long budgetMs);
}
