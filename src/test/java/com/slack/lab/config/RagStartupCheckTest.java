package com.slack.lab.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.VectorStore;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RagStartupCheckTest {

    final RagProperties rag = new RagProperties(true, "http://localhost:11434/v1", "bge-m3", 1024, 5_000, 3_000, true,
            false, false, List.of("localhost"));
    final VectorStore store = mock(VectorStore.class);

    @Test
    void 색인이_없거나_같은_모델이면_기동한다() {
        when(store.meta()).thenReturn(PortResult.ok(Optional.empty()));
        assertThatCode(() -> RagStartupCheck.verifyIndexMeta(store, rag)).doesNotThrowAnyException();

        when(store.meta()).thenReturn(PortResult.ok(Optional.of(new IndexMeta("bge-m3", 1024))));
        assertThatCode(() -> RagStartupCheck.verifyIndexMeta(store, rag)).doesNotThrowAnyException();
    }

    @Test
    void 모델이나_차원이_다르면_기동을_거부하고_무엇을_할지_알려준다() {
        when(store.meta()).thenReturn(PortResult.ok(Optional.of(new IndexMeta("nomic-embed-text", 768))));

        assertThatThrownBy(() -> RagStartupCheck.verifyIndexMeta(store, rag)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nomic-embed-text").hasMessageContaining("768").hasMessageContaining("전체 재색인");

        when(store.meta()).thenReturn(PortResult.ok(Optional.of(new IndexMeta("bge-m3", 768))));
        assertThatThrownBy(() -> RagStartupCheck.verifyIndexMeta(store, rag)).hasMessageContaining("768");
    }

    @Test
    void 메타를_읽지_못하면_조용히_넘기지_않고_기동을_거부한다() {
        when(store.meta()).thenReturn(PortResult.failed(ErrorInfo.of(ErrorCode.VECTOR_STORE_FAILED, "sqlstate=08001")));

        assertThatThrownBy(() -> RagStartupCheck.verifyIndexMeta(store, rag)).hasMessageContaining("sqlstate=08001");
    }

    static EmbeddingClient returning(EmbeddingResult r) {
        EmbeddingClient c = mock(EmbeddingClient.class);
        when(c.embed(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyLong())).thenReturn(r);
        return c;
    }

    @Test
    void 임베딩_프로브는_영구_실패만_거부하고_일시_실패는_경고로_넘긴다() {
        assertThatCode(() -> RagStartupCheck.verifyEmbedding(returning(new EmbeddingResult.Success(new float[1024], 5)), rag))
                .doesNotThrowAnyException();

        // 영구 실패(같은 요청을 다시 보내도 그대로): 차원 불일치·모델 없음(4xx)·응답 오류
        for (ErrorInfo e : new ErrorInfo[] {ErrorInfo.of(ErrorCode.EMBEDDING_DIMENSION_MISMATCH, "got=768 expected=1024"),
                ErrorInfo.of(ErrorCode.EMBEDDING_HTTP_ERROR, "404"), ErrorInfo.of(ErrorCode.EMBEDDING_RESPONSE_INVALID, "parse")}) {
            assertThatThrownBy(() -> RagStartupCheck.verifyEmbedding(returning(new EmbeddingResult.Failed(e, 5, false)), rag))
                    .as(e.text()).isInstanceOf(IllegalStateException.class).hasMessageContaining(e.code().code());
        }

        // 일시 실패: 서버가 아직 안 떴거나 느림 — 폴백이 흡수하므로 기동은 계속한다
        assertThatCode(() -> RagStartupCheck.verifyEmbedding(
                returning(new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_CONNECT_FAILED), 5, true)), rag))
                .doesNotThrowAnyException();
        assertThatCode(() -> RagStartupCheck.verifyEmbedding(
                returning(new EmbeddingResult.Failed(ErrorInfo.of(ErrorCode.EMBEDDING_HTTP_ERROR, "503"), 5, true)), rag))
                .doesNotThrowAnyException();
        assertThatCode(() -> RagStartupCheck.verifyEmbedding(returning(new EmbeddingResult.TimedOut(10_000)), rag))
                .doesNotThrowAnyException();
    }
}
