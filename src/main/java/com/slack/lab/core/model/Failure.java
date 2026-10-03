package com.slack.lab.core.model;

/**
 * 한 시도가 어디서(stage) 어떤 메시지(kind)를 처리하다 왜(error) 실패했는지. 핸들러가 만들어 결과 타입과 종료 기록에 그대로
 * 넘기므로, 워커가 문자열을 잘라 종류를 복원할 필요가 없다.
 */
public record Failure(ProcessingStage stage, MessageKind kind, ErrorInfo error) {}
