package com.slack.lab.core.port;

import com.slack.lab.core.model.RecoveryOutcome;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 결과 불명·DLQ 건을 사람이 다루는 저장소(M14). 자동 재발신 경로는 없다. 입력 본문은 노출하지 않는다 —
 * 목록 출력에 대화 내용이 새면 안 된다(PRD §7).
 */
public interface RecoveryStore {

    /** 복구 대상 한 건. 입력 본문은 담지 않는다. */
    record Entry(String list, String eventId, long preservedAtMs, Map<String, String> state, String reason) {}

    /** 답글이 달렸을 스레드 위치. 본문은 다루지 않는다. */
    record ThreadRef(String channel, String rootTs) {}

    /** DLQ와 복구 목록의 미해결 건을 보존 시각 순으로 돌려준다. */
    List<Entry> list();

    Map<String, String> stateOf(String eventId);

    Optional<ThreadRef> threadRef(String eventId);

    /** 스레드에서 답글이 실제로 있었음을 사람이 확인한 뒤 호출한다. 보존 입력과 목록 항목을 함께 지운다(B18). */
    RecoveryOutcome resolveCompleted(String eventId, String slackTs);

    /**
     * 읽기 전용 자동 조회가 답글을 찾아 완료 처리한다. 사람의 조치와 기록에서 구분할 수 있게 구현체가 단계(stage)를
     * 달리 남길 수 있다. 기본 구현은 사람의 완료와 같다.
     */
    default RecoveryOutcome resolveCompletedAutomatically(String eventId, String slackTs) {
        return resolveCompleted(eventId, slackTs);
    }

    /** 더 처리하지 않기로 닫는다. */
    RecoveryOutcome close(String eventId);

    /** 미전송이 확인된 건에 한 번의 수동 재실행을 승인한다. */
    RecoveryOutcome reprocess(String eventId);
}
