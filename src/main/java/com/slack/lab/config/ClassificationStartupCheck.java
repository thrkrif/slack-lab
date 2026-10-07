package com.slack.lab.config;

import com.slack.lab.core.model.ClassifyResult;
import com.slack.lab.core.port.RequestClassifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 분류를 켠 채 기동할 때의 사전 확인. 모델 ID 누락, Echo 클라이언트와의 조합(분류만 실제 Ollama를 부르는 어긋남), 모델 없음(4xx)은
 * 기동을 거부한다. 서버가 아직 안 떴거나 느린 것(연결 실패·5xx·기한 초과)과 첫 응답이 라벨이 아닌 것은 경고만 하고 넘어간다 —
 * 분류 실패는 장애 질문으로 폴백하므로 부가 기능 때문에 앱 핵심이 못 뜨면 안 된다.
 *
 * <p>검사는 빈 생성 시점에 하고, 큐 소비자는 이 빈을 먼저 만들게 해서({@code RabbitConfig}) 거부하기 전에 메시지를 처리하는
 * 일이 없게 한다({@link RagStartupCheck}와 같은 방식).
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.EVALUATOR, AppRole.ALL})
@ConditionalOnProperty(prefix = "classification", name = "enabled", havingValue = "true")
public class ClassificationStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(ClassificationStartupCheck.class);

    public ClassificationStartupCheck(ClassificationProperties props, LlmProperties llm, RequestClassifier classifier) {
        validate(props, llm);
        if (props.verifyOnStartup()) {
            probe(classifier, props);
        } else {
            log.warn("classification.verify-on-startup=false — 분류 모델 확인을 건너뜀");
        }
        log.info("요청 분류 켬 model={} timeout_ms={} (답변 모델과 {})", props.model(), props.timeoutMs(),
                props.model().equals(llm.model()) ? "같음" : "다름 — 16GB에서는 서로 축출할 수 있다(EXPERIMENT-LOG §39)");
    }

    static void validate(ClassificationProperties props, LlmProperties llm) {
        if (props.model().isBlank()) {
            throw new IllegalStateException("classification.enabled=true인데 classification.model이 비었다(`ollama list`로 확인한 모델 ID)");
        }
        if (llm.client() == LlmProperties.Client.ECHO) {
            throw new IllegalStateException("classification.enabled=true는 llm.client=ECHO와 함께 쓸 수 없다 — 분류만 실제 모델을 부르게 된다");
        }
    }

    static void probe(RequestClassifier classifier, ClassificationProperties props) {
        // 호출별 상한이 아니라 기동 확인용 마감(최소 10초)을 쓴다: 첫 호출은 모델 콜드 적재라 5초 상한을 넘을 수 있다(M35 실측).
        ClassifyResult r = classifier.probe(Math.max(props.timeoutMs(), 10_000));
        if (r instanceof ClassifyResult.Classified c) {
            log.info("분류 모델 확인됨 model={} elapsed_ms={}", props.model(), c.elapsedMs());
        } else if (r instanceof ClassifyResult.Failed f && !f.retryable()
                && f.error().code() == com.slack.lab.core.model.ErrorCode.CLASSIFY_FAILED) {
            throw new IllegalStateException("분류 모델 확인 실패: " + f.error().text() + " classification.model="
                    + props.model() + " (`ollama list` 확인)");
        } else {
            log.warn("분류 모델을 확인하지 못했지만 기동은 계속한다(분류는 장애 질문으로 폴백) result={}", r);
        }
    }
}
