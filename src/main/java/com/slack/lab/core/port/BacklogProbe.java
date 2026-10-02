package com.slack.lab.core.port;

import java.util.Map;

/** 적체 스냅샷에 들어갈 수치를 내놓는 어댑터(큐 깊이, 재시도·DLQ·복구 건수 등). 값을 못 구하면 예외를 던져도 된다. */
public interface BacklogProbe {

    /** 키는 로그 필드 이름(소문자·밑줄)이다. 순서를 유지하는 맵을 돌려준다. */
    Map<String, Long> snapshot();
}
