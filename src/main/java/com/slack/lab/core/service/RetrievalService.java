package com.slack.lab.core.service;

import com.slack.lab.core.model.DocumentHit;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.Retrieval;
import com.slack.lab.core.model.SearchResult;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.VectorStore;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 질문 → 임베딩 → 벡터 검색 → 임계값·문맥 상한 적용. 호출자가 넘긴 <b>한 개의 기한</b> 안에서 두 단계(임베딩, 검색)가 시간을
 * 나눠 쓴다: 임베딩이 쓴 만큼 검색 몫이 줄어든다. 기한을 넘기거나 실패하면 예외 없이 {@link Retrieval.Unavailable}로 돌려주고,
 * 기한 뒤에 도착한 결과는 쓰지 않는다. 검색 실패는 재시도 사유가 아니다 — 호출자는 RAG 없이 답한다(PLAN M28).
 */
public class RetrievalService {

    private static final Logger log = LoggerFactory.getLogger(RetrievalService.class);

    /**
     * @param searchDeadlineMs 검색(질의 임베딩 + 벡터 조회)에 쓸 수 있는 상한. 호출자가 준 예산이 더 작으면 그쪽을 따른다
     * @param minScore 이 유사도 미만인 조각은 주입하지 않는다(코사인 유사도, 초기값은 M30에서 튜닝 세트로 조정)
     * @param maxContextChars 주입하는 조각 글자 수의 합 상한(문맥 4K에서 K개를 넣기 위한 보호)
     */
    public record Config(long searchDeadlineMs, int topK, double minScore, int maxContextChars, int maxQueryChars) {
        public Config {
            if (searchDeadlineMs <= 0 || topK < 1 || maxContextChars < 1 || maxQueryChars < 1 || !Double.isFinite(minScore)
                    || minScore < -1.0 || minScore > 1.0) { // 코사인 유사도 범위 밖이면 항상 NoRelevant가 되는 조용한 실패다
                throw new IllegalArgumentException("rag 검색 설정이 올바르지 않다");
            }
        }
    }

    private final EmbeddingClient embedding;
    private final VectorStore store;
    private final Config config;

    public RetrievalService(EmbeddingClient embedding, VectorStore store, Config config) {
        this.embedding = embedding;
        this.store = store;
        this.config = config;
    }

    /** 임계값·문맥 상한을 적용하기 전의 검색 순위. 평가 하니스가 hit@K를 재려고 쓴다. */
    public sealed interface Ranking {

        record Ranked(List<DocumentHit> hits, long elapsedMs) implements Ranking {}

        record Unavailable(ErrorInfo error, long elapsedMs) implements Ranking {}
    }

    /**
     * @param budgetMs 호출 시점에 남은 LLM 단계 예산. 이보다 길게 쓰지 않는다
     * @return 예외를 던지지 않는다 — 포트 구현체의 예상 못한 예외도 {@link Retrieval.Unavailable}로 바꾼다. 검색 실패가 처리
     *     실패·재시도로 번지면 안 되기 때문이다(PLAN M28)
     */
    public Retrieval retrieve(String promptText, long budgetMs) {
        return switch (rank(promptText, budgetMs)) {
            case Ranking.Unavailable u -> new Retrieval.Unavailable(u.error(), u.elapsedMs());
            case Ranking.Ranked r -> {
                List<DocumentHit> picked = select(r.hits());
                yield picked.isEmpty() ? new Retrieval.NoRelevant(r.elapsedMs()) : new Retrieval.Found(picked, r.elapsedMs());
            }
        };
    }

    /** 운영 검색이 가져오는 조각 수. */
    public int topK() {
        return config.topK();
    }

    /** 질의 임베딩 + 벡터 검색. 같은 기한 규칙·예외 변환을 쓰며 임계값은 적용하지 않는다. */
    public Ranking rank(String promptText, long budgetMs) {
        return rank(promptText, budgetMs, config.topK());
    }

    /** 가져올 조각 수를 따로 정하는 버전(평가가 운영 topK와 무관하게 hit@K를 재려고 쓴다). */
    public Ranking rank(String promptText, long budgetMs, int k) {
        long start = System.nanoTime();
        try {
            return doRank(promptText, budgetMs, start, k);
        } catch (RuntimeException e) {
            log.warn("RAG 검색 중 예상 못한 예외 → RAG 없이 진행 reason={}", e.getClass().getSimpleName());
            return new Ranking.Unavailable(ErrorInfo.of(ErrorCode.UNEXPECTED_EXCEPTION, e), elapsedMs(start));
        }
    }

    private Ranking doRank(String promptText, long budgetMs, long start, int k) {
        long deadlineMs = Math.min(config.searchDeadlineMs(), budgetMs);
        if (deadlineMs <= 0) {
            return new Ranking.Unavailable(ErrorInfo.of(ErrorCode.EMBEDDING_NO_BUDGET), 0);
        }
        String query = RagPrompt.queryOf(promptText, config.maxQueryChars());
        if (query.isEmpty()) {
            return new Ranking.Ranked(List.of(), 0);
        }

        float[] vector;
        switch (embedding.embed(query, deadlineMs)) {
            case EmbeddingResult.Success ok -> vector = ok.vector();
            case EmbeddingResult.TimedOut t -> {
                return new Ranking.Unavailable(ErrorInfo.of(ErrorCode.EMBEDDING_TIMEOUT), elapsedMs(start));
            }
            case EmbeddingResult.Failed f -> {
                return new Ranking.Unavailable(f.error(), elapsedMs(start));
            }
        }

        long left = deadlineMs - elapsedMs(start);
        if (left <= 0) {
            return new Ranking.Unavailable(ErrorInfo.of(ErrorCode.EMBEDDING_TIMEOUT, "after_embed"), elapsedMs(start));
        }
        SearchResult searched = store.search(vector, k, left);
        long total = elapsedMs(start);
        List<DocumentHit> found;
        switch (searched) {
            case SearchResult.Success ok -> found = ok.hits();
            case SearchResult.TimedOut t -> {
                return new Ranking.Unavailable(ErrorInfo.of(ErrorCode.VECTOR_STORE_TIMEOUT), total);
            }
            case SearchResult.Failed f -> {
                return new Ranking.Unavailable(f.error(), total);
            }
        }
        // 기한을 넘겨 도착한 결과는 쓰지 않는다(호출자가 이미 다음 단계 예산을 계산했다).
        if (total > deadlineMs) {
            log.warn("검색 결과가 기한 뒤에 도착해 버린다 elapsed_ms={} deadline_ms={}", total, deadlineMs);
            return new Ranking.Unavailable(ErrorInfo.of(ErrorCode.VECTOR_STORE_TIMEOUT, "late_result"), total);
        }
        return new Ranking.Ranked(found, total);
    }

    /** 순위에서 프롬프트에 실제로 주입할 조각: 임계값 이상이고 문맥 글자 상한 안. 깨진 행은 쓰지 않는다. */
    public List<DocumentHit> select(List<DocumentHit> ranked) {
        List<DocumentHit> picked = new ArrayList<>();
        int chars = 0;
        for (DocumentHit hit : ranked) {
            // 저장소가 깨진 행을 돌려줘도(null 필드) 프롬프트 조립에서 터지지 않게 쓰지 않는다.
            if (hit == null || hit.documentId() == null || hit.title() == null || hit.text() == null
                    || hit.score() < config.minScore()) {
                continue;
            }
            if (hit.text().length() > config.maxContextChars()) {
                // 한 조각이 상한보다 크면(이전 설정의 큰 청크로 색인된 DB 등) 상한까지만 넣는다. 상한이 있으나 마나 해지면 4K 컨텍스트가 잘린다.
                hit = new DocumentHit(hit.documentId(), hit.title(), hit.text().substring(0, config.maxContextChars()), hit.score());
            }
            if (!picked.isEmpty() && chars + hit.text().length() > config.maxContextChars()) {
                break; // 상한을 넘기면 멈춘다
            }
            picked.add(hit);
            chars += hit.text().length();
        }
        return picked;
    }

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
