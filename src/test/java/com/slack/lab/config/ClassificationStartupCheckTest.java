package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.slack.lab.core.model.ClassifyResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.RequestKind;
import com.slack.lab.core.port.RequestClassifier;
import org.junit.jupiter.api.Test;

class ClassificationStartupCheckTest {

    private static ClassificationProperties props(String model) {
        return new ClassificationProperties(true, model, 5_000, "30m", true);
    }

    private static LlmProperties llm(LlmProperties.Client client) {
        return new LlmProperties(client, "http://localhost:11434/v1", "qwen2.5:3b", 512, "30m", 50_000, 3_000, true);
    }

    private static RequestClassifier returning(ClassifyResult r) {
        return (q, ms) -> r;
    }

    @Test
    void 모델_ID가_비면_기동을_거부한다() {
        assertThatThrownBy(() -> new ClassificationStartupCheck(props(""), llm(LlmProperties.Client.OLLAMA),
                returning(new ClassifyResult.Classified(RequestKind.SIMPLE, 1)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("classification.model");
    }

    @Test
    void Echo_클라이언트와는_함께_쓸_수_없다() {
        assertThatThrownBy(() -> new ClassificationStartupCheck(props("qwen2.5:3b"), llm(LlmProperties.Client.ECHO),
                returning(new ClassifyResult.Classified(RequestKind.SIMPLE, 1)))).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ECHO");
    }

    @Test
    void 모델_없음_같은_영구_실패는_기동을_거부한다() {
        var gone = new ClassifyResult.Failed(ErrorInfo.of(ErrorCode.CLASSIFY_FAILED, "http_404"), 5, false);
        assertThatThrownBy(() -> new ClassificationStartupCheck(props("nope"), llm(LlmProperties.Client.OLLAMA), returning(gone)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("http_404");
    }

    @Test
    void 서버가_느리거나_첫_응답이_라벨이_아니면_경고만_하고_기동한다() {
        var transientFail = new ClassifyResult.Failed(ErrorInfo.of(ErrorCode.CLASSIFY_TIMEOUT), 5_000, true);
        var invalid = new ClassifyResult.Failed(ErrorInfo.of(ErrorCode.CLASSIFY_INVALID_OUTPUT, "len=9"), 5, false);
        assertThatCode(() -> new ClassificationStartupCheck(props("m"), llm(LlmProperties.Client.OLLAMA), returning(transientFail)))
                .doesNotThrowAnyException();
        assertThatCode(() -> new ClassificationStartupCheck(props("m"), llm(LlmProperties.Client.OLLAMA), returning(invalid)))
                .doesNotThrowAnyException();
    }

    @Test
    void 기동_확인은_probe로_부르고_기본_구현은_classify로_위임한다() {
        java.util.List<String> calls = new java.util.ArrayList<>();
        RequestClassifier recording = new RequestClassifier() {
            @Override
            public ClassifyResult classify(String q, long ms) {
                calls.add("classify:" + q + ":" + ms);
                return new ClassifyResult.Classified(RequestKind.SIMPLE, 1);
            }

            @Override
            public ClassifyResult probe(long ms) {
                calls.add("probe:" + ms);
                return new ClassifyResult.Classified(RequestKind.SIMPLE, 1);
            }
        };
        new ClassificationStartupCheck(props("m"), llm(LlmProperties.Client.OLLAMA), recording);
        assertThat(calls).containsExactly("probe:10000");
        // 기본 구현(probe 미재정의)은 classify("ping", ms)로 위임한다.
        var delegating = new java.util.ArrayList<String>();
        RequestClassifier plain = (q, ms) -> {
            delegating.add(q + ":" + ms);
            return new ClassifyResult.Classified(RequestKind.SIMPLE, 1);
        };
        plain.probe(7_000);
        assertThat(delegating).containsExactly("ping:7000");
    }

    @Test
    void 확인을_끄면_분류기를_부르지_않는다() {
        var off = new ClassificationProperties(true, "m", 5_000, "30m", false);
        RequestClassifier boom = (q, ms) -> {
            throw new AssertionError("호출되면 안 된다");
        };
        assertThatCode(() -> new ClassificationStartupCheck(off, llm(LlmProperties.Client.OLLAMA), boom))
                .doesNotThrowAnyException();
        assertThat(true).isTrue();
    }
}
