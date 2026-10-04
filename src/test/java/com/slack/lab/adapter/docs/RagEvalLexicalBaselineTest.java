package com.slack.lab.adapter.docs;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.adapter.cli.EvalSetLoader;
import com.slack.lab.core.model.DocumentChunk;
import com.slack.lab.core.model.DocumentHit;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.EvalQuestion;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SearchResult;
import com.slack.lab.core.model.SourceDocument;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.VectorStore;
import com.slack.lab.core.service.MarkdownChunker;
import com.slack.lab.core.service.RagEvaluator;
import com.slack.lab.core.service.RetrievalService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * 평가 데이터의 정답 라벨이 "검색으로 찾을 수 있는" 수준인지 보는 <b>정합성 검사</b>이다. 실제 임베딩 대신 글자 2-gram 빈도 벡터와
 * 메모리 저장소를 쓴다 — 합격 판정(실제 bge-m3 측정)은 M30의 몫이고 여기 수치는 모델 품질이 아니다. 라벨이 엉뚱하면(질문과 정답
 * 문서가 전혀 안 맞으면) 이 어휘 기준선에서도 크게 틀린다.
 */
class RagEvalLexicalBaselineTest {

    static final int DIM = 2048;

    static float[] vec(String text) {
        float[] v = new float[DIM];
        String t = text.toLowerCase().replaceAll("\\s+", " ");
        for (int i = 0; i + 1 < t.length(); i++) {
            v[Math.floorMod(t.substring(i, i + 2).hashCode(), DIM)] += 1;
        }
        return v;
    }

    static double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }

    /** 검색만 되는 메모리 저장소. */
    static final class MemoryStore implements VectorStore {
        record Row(String id, String title, String text, float[] v) {}

        final List<Row> rows = new ArrayList<>();

        @Override
        public SearchResult search(float[] q, int topK, long remainingMs) {
            List<DocumentHit> hits = rows.stream().map(r -> new DocumentHit(r.id(), r.title(), r.text(), cosine(q, r.v())))
                    .sorted(Comparator.comparingDouble(DocumentHit::score).reversed()).limit(topK).toList();
            return new SearchResult.Success(hits, 0);
        }

        @Override
        public PortResult<Optional<IndexMeta>> meta() {
            throw new UnsupportedOperationException();
        }

        @Override
        public PortResult<Void> initMeta(IndexMeta m) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PortResult<Void> beginRebuild(IndexMeta m) {
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
        public PortResult<Void> replaceDocument(String d, String t, String h, List<DocumentChunk> c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PortResult<Void> deleteDocuments(Collection<String> ids) {
            throw new UnsupportedOperationException();
        }
    }

    @Test
    void 어휘_기준선에서도_최종_정답_질문의_대부분이_정답_문서를_상위에서_찾는다() throws Exception {
        var store = new MemoryStore();
        var chunker = new MarkdownChunker(800, 100);
        var docs = ((PortResult.Success<List<SourceDocument>>) new LocalMarkdownDocumentSource("docs/rag-eval/documents").list()).value();
        for (SourceDocument d : docs) {
            for (String c : chunker.chunk(d.content())) {
                store.rows.add(new MemoryStore.Row(d.id(), d.title(), c, vec(c)));
            }
        }
        EmbeddingClient embedding = (text, ms) -> new EmbeddingResult.Success(vec(text), 0);
        var retrieval = new RetrievalService(embedding, store, new RetrievalService.Config(5_000, 3, 0.0, 3_000, 500));
        var questions = EvalSetLoader.load(Path.of("docs/rag-eval/questions.json"), new ObjectMapper());

        var report = new RagEvaluator(retrieval, 3).evaluate(questions);

        var fin = report.set(EvalQuestion.FINAL);
        // final 순위·점수를 빌드 로그에 찍지 않는다(조정하면서 final을 보게 되는 경로를 만들지 않는다). 집계만 남긴다.
        System.out.println("어휘 기준선(모델 품질 아님) final hit " + fin.hits() + "/" + fin.answerable());
        // 라벨 정합성 검사이므로 합격선(20/24)이 아니라 느슨한 하한만 둔다. 실제 임베딩 판정은 M30.
        assertThat(fin.hits()).as("어휘만으로도 정답 24개 중 이만큼은 찾아야 라벨이 말이 된다").isGreaterThanOrEqualTo(14);
        assertThat(fin.unavailable()).isZero();
    }
}
