package com.slack.lab.core.port;

import com.slack.lab.core.model.DocumentChunk;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SearchResult;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 과거 장애 문서의 벡터 저장·검색(3단계 RAG). RAG를 끄면 사용되지 않고, 켜면 중복 억제와 같은 Postgres(pgvector)가
 * 구현체가 된다(ADR-9). 구현체의 연결·잠금·쿼리 취소 같은 인프라 수명은 어댑터가 쥔다.
 */
public interface VectorStore {

    /** 색인의 모델·차원·청킹. 아직 한 번도 색인하지 않았으면 비어 있다(첫 색인이 만든다). */
    PortResult<Optional<IndexMeta>> meta();

    /** 첫 색인 때 메타를 기록한다. 이미 있으면 같은 값일 때만 성공하고 다르면 {@code INDEX_META_MISMATCH}다. */
    PortResult<Void> initMeta(IndexMeta meta);

    /**
     * 모델·차원이 바뀐 전체 재색인용 단계적 교체. 시작하면 새 메타의 <b>대기 세대</b>가 만들어지고, 이후
     * {@link #replaceDocument}·{@link #deleteDocuments}는 {@link #commitRebuild()} 전까지 대기 세대에만 쓴다.
     * 검색·{@link #meta()}는 커밋 전까지 기존 세대를 본다 — 재색인이 중간에 죽거나 {@link #abortRebuild()}해도 기존
     * 색인은 그대로다. 이미 진행 중인 재색인이 있으면 실패한다.
     */
    PortResult<Void> beginRebuild(IndexMeta newMeta);

    /** 대기 세대를 한 번에 게시한다(메타와 문서가 함께 바뀐다). 진행 중인 재색인이 없으면 실패한다. */
    PortResult<Void> commitRebuild();

    /** 대기 세대를 버린다. 진행 중인 재색인이 없으면 아무 일도 하지 않고 성공한다. */
    PortResult<Void> abortRebuild();

    /** 문서 ID → 저장된 내용 해시. 증분 색인과 삭제 감지에 쓴다. */
    PortResult<Map<String, String>> indexedHashes();

    /**
     * 한 문서의 청크를 통째로 바꾼다. 기존 청크 삭제와 새 청크 삽입은 한 트랜잭션이어야 한다 — 중간에 죽어도 검색에서
     * 그 문서가 사라지거나 옛 조각과 새 조각이 섞이면 안 된다. 청크 벡터 차원이 메타와 다르면 거부한다.
     */
    PortResult<Void> replaceDocument(String documentId, String title, String contentHash,
            List<DocumentChunk> chunks);

    PortResult<Void> deleteDocuments(Collection<String> documentIds);

    /**
     * 질의 벡터와 가까운 청크를 유사도 내림차순으로 최대 {@code topK}건 돌려준다.
     *
     * @param remainingMs 연결 대기와 쿼리 실행을 합친 기한. 넘으면 쿼리를 취소하고
     *     {@link SearchResult.TimedOut}으로 돌려준다(HTTP 타임아웃만으로는 DB 쪽 대기를 막지 못한다)
     */
    SearchResult search(float[] query, int topK, long remainingMs);
}
