package com.slack.lab.core.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReferenceListTest {

    @Test
    void 같은_문서의_청크_여러_건은_한_번만_나오고_주입_순서를_유지한다() {
        var list = ReferenceList.fromInjected(List.of(
                new DocumentHit("DB-003", "커넥션 풀", "a", 0.9),
                new DocumentHit("OPS-012", "점검 절차", "b", 0.8),
                new DocumentHit("DB-003", "커넥션 풀", "c", 0.7)));

        assertThat(list.references()).extracting(ReferenceList.Reference::documentId)
                .containsExactly("DB-003", "OPS-012");
    }

    @Test
    void 주입이_없으면_비어_있어_출처_줄을_생략할_수_있다() {
        assertThat(ReferenceList.fromInjected(List.of()).isEmpty()).isTrue();
        assertThat(new ReferenceList(List.of()).isEmpty()).isTrue();
    }

    @Test
    void 검색_성공은_빈_결과와_장애를_타입으로_구분한다() {
        SearchResult none = new SearchResult.Success(List.of(), 3);
        SearchResult failed = new SearchResult.Failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED), 3);

        assertThat(none).isInstanceOf(SearchResult.Success.class);
        assertThat(((SearchResult.Success) none).hits()).isEmpty();
        assertThat(failed).isNotInstanceOf(SearchResult.Success.class);
    }

    @Test
    void 벡터_배열은_밖에서_바꿔도_값이_변하지_않는다() {
        float[] src = {1f, 2f};
        var chunk = new DocumentChunk(0, "t", src);
        var emb = new EmbeddingResult.Success(src, 1);

        src[0] = 9f;
        chunk.embedding()[1] = 9f;
        emb.vector()[1] = 9f;

        assertThat(chunk.embedding()).containsExactly(1f, 2f);
        assertThat(emb.vector()).containsExactly(1f, 2f);
    }
}
