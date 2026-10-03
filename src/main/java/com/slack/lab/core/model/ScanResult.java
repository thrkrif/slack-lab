package com.slack.lab.core.model;

import java.util.List;

/**
 * 스레드에서 이벤트의 봇 답글을 찾은 결과.
 *
 * @param error 조회 실패 사유(성공이면 null)
 * @param complete 스레드 끝까지 읽었는지. false면 {@code matches}가 비어 있어도 "없음"이 아니다.
 */
public record ScanResult(List<ReplyMatch> matches, String error, boolean complete) {
    public boolean failed() {
        return error != null;
    }
}
