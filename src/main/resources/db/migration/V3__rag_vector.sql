-- 3단계 RAG 벡터 저장 (M25, ADR-9: 중복 억제와 같은 Postgres에 pgvector).
-- 차원은 설정으로 받는 값이라 컬럼은 차원 없는 vector로 두고(무인덱스 전수 검색, 평가 규모 20문서), 세대마다 모델·차원을
-- 기록해 어댑터가 쓰기·검색 때 검증한다. 인덱스(HNSW 등)는 차원이 고정되는 규모에서 후속 검토한다.
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE rag_generation (
    generation bigserial PRIMARY KEY,
    model_id   text   NOT NULL,
    dimension  int    NOT NULL CHECK (dimension > 0),
    created_at bigint NOT NULL
);

-- 단일 행. live는 검색이 보는 세대, pending은 전체 재색인(모델·차원 변경) 중에 쓰는 대기 세대다.
CREATE TABLE rag_index_state (
    id                 int PRIMARY KEY CHECK (id = 1),
    live_generation    bigint REFERENCES rag_generation (generation),
    pending_generation bigint REFERENCES rag_generation (generation)
);
INSERT INTO rag_index_state (id) VALUES (1);

CREATE TABLE rag_document (
    generation   bigint NOT NULL REFERENCES rag_generation (generation) ON DELETE CASCADE,
    document_id  text   NOT NULL,
    title        text   NOT NULL,
    content_hash text   NOT NULL,
    PRIMARY KEY (generation, document_id)
);

CREATE TABLE rag_chunk (
    generation  bigint NOT NULL,
    document_id text   NOT NULL,
    chunk_index int    NOT NULL,
    text        text   NOT NULL,
    embedding   vector NOT NULL,
    PRIMARY KEY (generation, document_id, chunk_index),
    FOREIGN KEY (generation, document_id) REFERENCES rag_document (generation, document_id) ON DELETE CASCADE
);
