package com.slack.lab.core.port;

/** 수신 서버가 이벤트를 수락하려면 살아 있어야 하는 의존 시스템 하나(큐·저장소 등). */
public interface HealthProbe {

    /** /health 응답의 키. */
    String name();

    boolean up();
}
