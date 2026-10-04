package com.slack.lab.config;

import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.VectorStore;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * RAG를 켠 채 기동할 때의 사전 확인: ① 임베딩 모델이 있고 차원이 설정과 같은가 ② (워커) 색인이 지금 설정의 모델·차원으로
 * 만들어졌는가. 어긋나면 기동을 거부한다 — 모델을 바꾸면 기존 벡터와 새 질의 벡터가 서로 비교 불가능해지는데, 모르고 쓰면
 * 검색이 조용히 엉터리가 된다(규칙 4).
 *
 * <p>검사는 빈 생성 시점에 하고, 큐 소비자는 이 빈을 먼저 만들게 해서({@code RabbitConfig}) 거부하기 전에 메시지를 처리하는
 * 일이 없게 한다(ApplicationRunner로 두면 소비자가 이미 돈다). 색인 CLI(INDEXER)는 ②를 하지 않는다 — 전체 재색인이 바로
 * 이 불일치를 풀기 위한 경로다.
 */
@Component
@ConditionalOnRole({AppRole.WORKER, AppRole.INDEXER, AppRole.ALL})
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagStartupCheck {

    private static final Logger log = LoggerFactory.getLogger(RagStartupCheck.class);

    public RagStartupCheck(RagProperties rag, EmbeddingClient embedding, VectorStore store, Environment env) {
        if (rag.verifyOnStartup()) {
            verifyEmbedding(embedding, rag);
        } else {
            log.warn("rag.verify-on-startup=false — 임베딩 모델·차원 확인을 건너뜀");
        }
        if (OnRoleCondition.currentRole(env) != AppRole.INDEXER) {
            verifyIndexMeta(store, rag);
        }
    }

    /**
     * 한 번 호출해 모델 존재와 차원을 확인한다. 같은 요청을 다시 보내도 그대로인 영구 실패(모델 없음 4xx, 차원 불일치, 응답
     * 오류)는 거부하고, 서버가 아직 안 떴거나 느린 것(연결 실패·5xx·기한 초과)은 경고만 하고 넘어간다 — 질의 쪽 폴백이 흡수하므로
     * 부가 기능 때문에 앱 핵심(Slack 응답)이 못 뜨면 안 된다.
     */
    static void verifyEmbedding(EmbeddingClient client, RagProperties rag) {
        EmbeddingResult r = client.embed("ping", Math.max(rag.searchDeadlineMs(), 10_000));
        if (r instanceof EmbeddingResult.Success s) {
            log.info("임베딩 모델 확인됨 model={} dimension={} elapsed_ms={}", rag.embeddingModel(), rag.embeddingDimension(),
                    s.elapsedMs());
        } else if (r instanceof EmbeddingResult.Failed f && !f.retryable()) {
            throw new IllegalStateException("임베딩 확인 실패: " + f.error().text() + " rag.embedding-model="
                    + rag.embeddingModel() + " rag.embedding-dimension=" + rag.embeddingDimension()
                    + " rag.embedding-base-url=" + rag.embeddingBaseUrl() + " (`ollama list`·모델 차원 확인)");
        } else {
            log.warn("임베딩 서버를 확인하지 못했지만 기동은 계속한다(질의는 RAG 없이 답하는 폴백으로 흡수) result={}", r);
        }
    }

    static void verifyIndexMeta(VectorStore store, RagProperties rag) {
        PortResult<Optional<IndexMeta>> r = store.meta();
        if (r instanceof PortResult.Failed<Optional<IndexMeta>> failed) {
            throw new IllegalStateException("RAG 색인 메타를 읽지 못했다: " + failed.error().text());
        }
        Optional<IndexMeta> meta = ((PortResult.Success<Optional<IndexMeta>>) r).value();
        if (meta.isEmpty()) {
            log.info("RAG 색인이 아직 없다(첫 색인 전) — 검색은 근거 없이 진행된다");
            return;
        }
        IndexMeta configured = new IndexMeta(rag.embeddingModel(), rag.embeddingDimension());
        if (!meta.get().equals(configured)) {
            throw new IllegalStateException("RAG 색인은 model=" + meta.get().modelId() + " dimension=" + meta.get().dimension()
                    + " 로 만들어졌는데 설정은 model=" + configured.modelId() + " dimension=" + configured.dimension()
                    + " 이다. 설정을 되돌리거나 전체 재색인(모델·차원 변경)을 먼저 한다");
        }
        log.info("RAG 색인 메타 확인됨 model={} dimension={}", configured.modelId(), configured.dimension());
    }
}
