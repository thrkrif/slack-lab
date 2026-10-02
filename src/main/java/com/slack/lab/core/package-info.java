/**
 * 코어(도메인 모델·포트·응용 서비스). 브로커·저장소·LLM·채팅 서비스가 무엇인지 모르고 인터페이스(포트)로만 다룬다.
 * HTTP·JSON·큐·DB 같은 인프라 타입을 두지 않는다(AGENTS.md 규칙 2의 확장). {@code ArchitectureTest}가 빌드에서 강제한다.
 */
package com.slack.lab.core;
