package com.slack.lab.config;

import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 설정값 사이의 불변식을 기동 시 검사한다. 각 값은 따로 보면 유효해도 조합이 어긋나면 기한·임대·회수 규칙이
 * 조용히 깨진다(1단계 후속 과제, PLAN 2단계 M10). 위반하면 기동을 멈춘다.
 */
@Component
public class StartupInvariants {

    public StartupInvariants(LlmProperties llm, SlackProperties slack, ProcessingProperties processing,
            StateProperties state, RetryProperties retry) {
        List<String> violations = check(llm, slack, processing, state, retry);
        if (!violations.isEmpty()) {
            throw new IllegalStateException("설정 불변식 위반: " + String.join("; ", violations));
        }
    }

    static List<String> check(LlmProperties llm, SlackProperties slack, ProcessingProperties processing,
            StateProperties state, RetryProperties retry) {
        List<String> v = new ArrayList<>();
        // 큰 값의 합이 넘쳐 음수가 되면 불변식을 우회하므로 넘침도 위반으로 본다.
        if (sumExceeds(llm.deadlineMs(), slack.sendDeadlineMs(), processing.totalDeadlineMs())) {
            v.add("llm.deadline-ms + slack.send-deadline-ms <= processing.total-deadline-ms 이어야 한다");
        }
        // 갱신 한 번이 실패해도 임대를 잃지 않게 임대 안에 갱신 기회를 세 번 둔다.
        if (state.renewMs() > state.leaseMs() / 3) {
            v.add("state.renew-ms <= state.lease-ms / 3 이어야 한다");
        }
        if (retry.backoffMs().size() < retry.maxRetries()) {
            v.add("retry.backoff-ms 항목 수 >= retry.max-retries 이어야 한다");
        }
        return v;
    }

    /** a + b > limit. 합이 long 범위를 넘치면 true다. */
    private static boolean sumExceeds(long a, long b, long limit) {
        try {
            return Math.addExact(a, b) > limit;
        } catch (ArithmeticException overflow) {
            return true;
        }
    }
}
