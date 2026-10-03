package com.slack.lab.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ErrorModelTest {

    @Test
    void 저장되는_고정_코드는_모든_enum에서_겹치지_않고_소문자_밑줄_형식이다() {
        for (var codes : new Stream[] {Arrays.stream(ErrorCode.values()).map(ErrorCode::code),
                Arrays.stream(ProcessingStage.values()).map(ProcessingStage::code),
                Arrays.stream(MessageKind.values()).map(MessageKind::code)}) {
            var list = codes.toList();
            assertThat(new HashSet<>(list)).hasSameSizeAs(list);
            assertThat(list).allMatch(c -> ((String) c).matches("[a-z]+(_[a-z]+)*"));
        }
    }

    @Test
    void 오류_한_줄_표기는_코드와_외부_상세를_함께_보존한다() {
        assertThat(ErrorInfo.of(ErrorCode.SLACK_API_ERROR, "invalid_auth").text()).isEqualTo("slack_api_error:invalid_auth");
        assertThat(ErrorInfo.of(ErrorCode.LLM_LANGUAGE_VIOLATION).text()).isEqualTo("llm_language_violation");
        assertThat(ErrorInfo.of(ErrorCode.QUEUE_PUBLISH_FAILED, new java.io.IOException("본문은 넣지 않는다")).detail())
                .isEqualTo("IOException");
    }

    @Test
    void 종료_기록은_실패의_종류와_단계를_문자열_파싱_없이_그대로_옮긴다() {
        Failure f = new Failure(ProcessingStage.SEND, MessageKind.FAILURE_NOTICE,
                ErrorInfo.of(ErrorCode.SLACK_TIMEOUT, "read_timeout"));

        Finalization unknown = Finalization.unknown(f);
        Finalization dead = Finalization.dead(f);

        assertThat(unknown.kind()).isEqualTo(MessageKind.FAILURE_NOTICE);
        assertThat(dead.kind()).isEqualTo(MessageKind.FAILURE_NOTICE);
        assertThat(unknown.stage()).isEqualTo(ProcessingStage.SEND);
        assertThat(unknown.error().code()).isEqualTo(ErrorCode.SLACK_TIMEOUT);
        assertThat(Finalization.completed("1.1", MessageKind.ANSWER).error()).isNull();
    }
}
