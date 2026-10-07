package com.slack.lab.config;

/**
 * 한 코드베이스를 역할별 프로세스로 나눠 띄운다(PLAN 2단계 T1). {@code ALL}은 1단계처럼 한 프로세스가 수신·처리를
 * 모두 하는 개발용 기본값이다. 2단계 M12에서 수신과 처리가 큐로 분리되면 {@code ALL}도 큐를 거친다.
 */
public enum AppRole {
    RECEIVER, WORKER, REACTOR, RECOVERY,
    /** RAG 색인 CLI(3단계). 복구처럼 웹 포트 없이 일회성으로 돈다. */
    INDEXER,
    /** 분류 평가 CLI(4단계). Postgres·큐·Slack 없이 분류 모델만 부른다(DB가 필요한 INDEXER와 분리). */
    EVALUATOR,
    ALL;

    /** 웹 서버가 필요한 역할. ngrok에 노출되는 포트는 수신 서버 하나뿐이어야 한다. */
    public boolean servesHttp() {
        return this == RECEIVER || this == ALL;
    }
}
