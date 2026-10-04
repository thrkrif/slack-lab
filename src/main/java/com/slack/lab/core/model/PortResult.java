package com.slack.lab.core.model;

/** 값을 돌려주는 단순 포트 호출의 성공/실패. 기한·재시도 구분이 필요한 호출은 전용 결과 타입을 쓴다. */
public sealed interface PortResult<T> {

    record Success<T>(T value) implements PortResult<T> {}

    record Failed<T>(ErrorInfo error) implements PortResult<T> {}

    static <T> PortResult<T> ok(T value) {
        return new Success<>(value);
    }

    static <T> PortResult<T> failed(ErrorInfo error) {
        return new Failed<>(error);
    }
}
