package com.slack.lab.event;

/** 처리 권한 선점 결과. 중복이면 왜 거절됐는지(기존 상태)를 함께 돌려줘 로그에 남길 수 있게 한다. */
public sealed interface ClaimResult {

    record Claimed(AttemptHandle handle) implements ClaimResult {}

    record Duplicate(ProcessingState existing) implements ClaimResult {}
}
