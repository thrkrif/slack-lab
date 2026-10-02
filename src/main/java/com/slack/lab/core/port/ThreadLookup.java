package com.slack.lab.core.port;

import com.slack.lab.core.model.BotIdentity;
import com.slack.lab.core.model.FetchResult;
import com.slack.lab.core.model.ScanResult;

/** 채팅 스레드를 읽는다(읽기 전용). 복구 확인과 LLM 문맥 조립이 쓴다. 본문은 읽어도 로그에 남기지 않는다. */
public interface ThreadLookup {

    /** 스레드에서 해당 event_id의 우리 봇 답글(메타데이터 일치)을 찾는다. */
    ScanResult findReplies(String channel, String threadTs, String eventId);

    /** 스레드 메시지를 시간순으로 읽는다. {@code deadlineMs} 안에 끝내지 못하면 실패로 돌려준다. */
    FetchResult fetchMessages(String channel, String threadTs, long deadlineMs);

    /** 이 봇의 식별자. 알 수 없으면 null이고 호출자는 식별 없이도 동작해야 한다. */
    BotIdentity identity(long timeoutMs);
}
