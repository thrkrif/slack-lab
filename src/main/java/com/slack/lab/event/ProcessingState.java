package com.slack.lab.event;

/** ARCHITECTURE §3.2의 상태. COMPLETED·FAILED·UNKNOWN은 종료 상태다. */
public enum ProcessingState {
    PROCESSING, SENDING, COMPLETED, FAILED, UNKNOWN;

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED || this == UNKNOWN;
    }
}
