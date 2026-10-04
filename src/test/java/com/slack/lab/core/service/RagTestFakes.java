package com.slack.lab.core.service;

import com.slack.lab.core.model.DocumentChunk;
import com.slack.lab.core.model.DocumentHit;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SearchResult;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.VectorStore;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;

/** RAG 질의 경로 테스트용 가짜. 검색만 쓰는 쪽이라 쓰기 계약은 지원하지 않는다. */
final class RagTestFakes {

    private RagTestFakes() {}

    static EmbeddingClient embedding(Function<String, EmbeddingResult> fn) {
        return (text, remainingMs) -> fn.apply(text);
    }

    static EmbeddingClient okEmbedding() {
        return embedding(t -> new EmbeddingResult.Success(new float[] {1, 0}, 1));
    }

    static VectorStore store(BiFunction<float[], Long, SearchResult> search) {
        return new VectorStore() {
            @Override
            public SearchResult search(float[] query, int topK, long remainingMs) {
                return search.apply(query, remainingMs);
            }

            @Override
            public PortResult<Optional<IndexMeta>> meta() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PortResult<Void> initMeta(IndexMeta meta) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PortResult<Void> beginRebuild(IndexMeta newMeta) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PortResult<Void> commitRebuild() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PortResult<Void> abortRebuild() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PortResult<Map<String, String>> indexedHashes() {
                throw new UnsupportedOperationException();
            }

            @Override
            public PortResult<Void> replaceDocument(String documentId, String title, String contentHash,
                    List<DocumentChunk> chunks) {
                throw new UnsupportedOperationException();
            }

            @Override
            public PortResult<Void> deleteDocuments(Collection<String> documentIds) {
                throw new UnsupportedOperationException();
            }
        };
    }

    static VectorStore storeReturning(DocumentHit... hits) {
        return store((q, ms) -> new SearchResult.Success(List.of(hits), 1));
    }

    static DocumentHit hit(String id, String title, String text, double score) {
        return new DocumentHit(id, title, text, score);
    }

    static RetrievalService.Config config() {
        return new RetrievalService.Config(5_000, 3, 0.5, 3_000, 500);
    }
}
