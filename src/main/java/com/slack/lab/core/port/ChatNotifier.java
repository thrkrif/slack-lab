package com.slack.lab.core.port;

import com.slack.lab.core.model.ReactionResult;
import com.slack.lab.core.model.ReplyMetadata;
import com.slack.lab.core.model.SlackSendResult;

/** 채팅 서비스로 나가는 쓰기(답글·반응). 구현체는 전송 결과를 성공/미전송 확실/결과 불명으로 분류해 돌려준다. */
public interface ChatNotifier {

    /**
     * @param remainingMs 이 호출 시작 시점부터 허용되는 남은 시간. 예산이 없으면 발신을 시작하지 않는다.
     * @param metadata 결과 불명 뒤 "이 시도가 보낸 답글"을 찾는 단서. null이면 붙이지 않는다.
     */
    SlackSendResult postMessage(String channel, String threadTs, String text, long remainingMs,
            ReplyMetadata metadata);

    /** 보조 기능이라 재시도·결과 불명 분류를 따로 두지 않는다. 이미 붙어 있는 것은 성공으로 본다. */
    ReactionResult addReaction(String channel, String ts, String emoji);
}
