package com.slack.lab.core.model;

/**
 * 오류 원인 분류와 외부 상세. {@code detail}은 진단용이라 의미를 해석하지 않는다(예: Slack {@code invalid_auth},
 * HTTP 상태 {@code 503}, 예외 클래스 이름). 없으면 빈 문자열이다.
 */
public record ErrorInfo(ErrorCode code, String detail) {

    public ErrorInfo {
        if (code == null) {
            throw new IllegalArgumentException("code");
        }
        detail = detail == null ? "" : detail;
    }

    public static ErrorInfo of(ErrorCode code) {
        return new ErrorInfo(code, "");
    }

    public static ErrorInfo of(ErrorCode code, String detail) {
        return new ErrorInfo(code, detail);
    }

    /** 예외 클래스 이름을 상세로 쓴다. 메시지는 사용자 입력을 담을 수 있어 넣지 않는다. */
    public static ErrorInfo of(ErrorCode code, Throwable cause) {
        return new ErrorInfo(code, cause == null ? "" : cause.getClass().getSimpleName());
    }

    /** 로그·보존 사유용 한 줄. */
    public String text() {
        return detail.isEmpty() ? code.code() : code.code() + ":" + detail;
    }
}
