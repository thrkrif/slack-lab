package com.slack.lab.core.service;

import com.slack.lab.core.model.DocumentChunk;
import com.slack.lab.core.model.EmbeddingResult;
import com.slack.lab.core.model.ErrorCode;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.IndexMeta;
import com.slack.lab.core.model.IndexReport;
import com.slack.lab.core.model.IndexReport.FailedDocument;
import com.slack.lab.core.model.IndexReport.Outcome;
import com.slack.lab.core.model.PortResult;
import com.slack.lab.core.model.SourceDocument;
import com.slack.lab.core.port.DocumentSource;
import com.slack.lab.core.port.EmbeddingClient;
import com.slack.lab.core.port.IndexLock;
import com.slack.lab.core.port.VectorStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 문서 출처를 벡터 저장소에 맞춘다(증분 색인). CLI는 이 서비스를 부르기만 하는 얇은 어댑터다 — 나중에 스케줄러가 같은
 * 로직을 호출할 수 있다.
 *
 * <p>규칙: ① 한 번에 하나만(잠금) ② 문서 단위로 원자적으로 바꾼다(저장소 계약) ③ 한 문서가 실패해도 나머지는 계속하고 마지막에
 * 실패 목록과 비0 종료 코드로 알린다 — 조용히 건너뛰지 않는다 ④ 증분 키 = 내용 해시 + 청킹 설정 + 모델·차원, 하나라도 바뀌면
 * 그 문서를 다시 색인한다 ⑤ 출처에 없는 문서만 지우되, 지울 비율이 임계를 넘으면 아무것도 쓰지 않고 멈춘다(경로 설정 실수로
 * 폴더가 비어 보일 때 색인 전체가 지워지는 사고 방어) ⑥ 모델·차원이 바뀐 전체 재색인은 대기 세대에 쓰고 전부 성공했을 때만
 * 게시한다.
 */
public class IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingService.class);

    /**
     * @param maxDeleteRatio 이번 실행으로 지울 문서 수 / 색인된 문서 수가 이 값을 넘으면 중단(0.0~1.0)
     * @param embedTimeoutMs 청크 하나를 임베딩할 때 기다리는 최대 시간
     * @param embedAttempts 재시도 가능한 임베딩 실패를 포함한 청크당 최대 시도 수
     */
    public record Config(String modelId, int dimension, int chunkSize, int chunkOverlap, double maxDeleteRatio,
            long embedTimeoutMs, int embedAttempts) {
        public Config {
            if (modelId == null || modelId.isBlank() || dimension <= 0) {
                throw new IllegalArgumentException("모델 ID와 차원이 필요하다");
            }
            if (!Double.isFinite(maxDeleteRatio) || maxDeleteRatio < 0 || maxDeleteRatio > 1) {
                throw new IllegalArgumentException("max-delete-ratio는 0.0~1.0이어야 한다");
            }
            if (embedTimeoutMs <= 0 || embedAttempts < 1) {
                throw new IllegalArgumentException("embed-timeout-ms > 0, embed-attempts >= 1 이어야 한다");
            }
        }
    }

    private final DocumentSource source;
    private final EmbeddingClient embedding;
    private final VectorStore store;
    private final IndexLock lock;
    private final Config config;
    private final MarkdownChunker chunker;
    private final IndexMeta meta;
    private final String chunkingHash;

    public IndexingService(DocumentSource source, EmbeddingClient embedding, VectorStore store, IndexLock lock,
            Config config) {
        this.source = source;
        this.embedding = embedding;
        this.store = store;
        this.lock = lock;
        this.config = config;
        this.chunker = new MarkdownChunker(config.chunkSize(), config.chunkOverlap());
        this.meta = new IndexMeta(config.modelId(), config.dimension());
        this.chunkingHash = sha256("md-v1|" + config.chunkSize() + "|" + config.chunkOverlap());
    }

    /**
     * @param rebuild 모델·차원 변경(또는 강제) 전체 재색인
     * @param confirmDelete 삭제 임계 비율 초과를 알고도 진행
     */
    public IndexReport run(boolean rebuild, boolean confirmDelete) {
        Optional<IndexLock.Handle> handle = lock.tryAcquire();
        if (handle.isEmpty()) {
            return IndexReport.locked();
        }
        try (IndexLock.Handle ignored = handle.get()) {
            return runLocked(handle.get(), rebuild, confirmDelete);
        }
    }

    private IndexReport runLocked(IndexLock.Handle held, boolean rebuild, boolean confirmDelete) {
        PortResult<List<SourceDocument>> listed = source.list();
        if (listed instanceof PortResult.Failed<List<SourceDocument>> f) {
            return IndexReport.sourceFailed(f.error());
        }
        List<SourceDocument> all = ((PortResult.Success<List<SourceDocument>>) listed).value();

        // 잠금을 쥔 동안 남아 있는 대기 세대는 이전 실행이 죽으며 남긴 것이다. 두면 일반 쓰기가 그쪽으로 가 게시되지 않는다.
        PortResult<Void> aborted = store.abortRebuild();
        if (aborted instanceof PortResult.Failed<Void> f) {
            return IndexReport.storeOrMetaFailed("abort_stale_rebuild:" + f.error().text());
        }

        PortResult<Optional<IndexMeta>> current = store.meta();
        if (current instanceof PortResult.Failed<Optional<IndexMeta>> f) {
            return IndexReport.storeOrMetaFailed("meta:" + f.error().text());
        }
        Optional<IndexMeta> existing = ((PortResult.Success<Optional<IndexMeta>>) current).value();
        if (existing.isPresent() && !rebuild && !existing.get().equals(meta)) {
            return IndexReport.storeOrMetaFailed("색인은 model=" + existing.get().modelId() + " dimension="
                    + existing.get().dimension() + " 로 만들어졌고 설정은 model=" + meta.modelId() + " dimension="
                    + meta.dimension() + " 이다. 전체 재색인(--rebuild)으로 바꾼다");
        }

        // 내용이 없는 문서는 청크가 없어 색인할 것이 없다. 출처에 없는 것으로 취급해 이미 색인돼 있었다면 지운다.
        List<SourceDocument> docs = new ArrayList<>();
        int skippedEmpty = 0;
        for (SourceDocument d : all) {
            if (chunker.chunk(d.content()).isEmpty()) {
                skippedEmpty++;
            } else {
                docs.add(d);
            }
        }

        // 삭제 방어는 전체 재색인에도 적용한다. 재색인은 대기 세대에 새로 쓰고 게시하므로, 출처가 비어 보이는 사고에서 이 검사가
        // 없으면 확인 없이 기존 색인 전체가 사라진다. 그래서 대기 세대를 만들기 전에 지금 게시된 색인을 기준으로 센다.
        Map<String, String> liveIndexed = Map.of();
        if (existing.isPresent()) {
            PortResult<Map<String, String>> liveHashes = store.indexedHashes();
            if (liveHashes instanceof PortResult.Failed<Map<String, String>> f) {
                return IndexReport.storeOrMetaFailed("indexed_hashes:" + f.error().text());
            }
            liveIndexed = ((PortResult.Success<Map<String, String>>) liveHashes).value();
        }
        Set<String> present = new HashSet<>();
        for (SourceDocument d : docs) {
            present.add(d.id());
        }
        List<String> missing = new ArrayList<>();
        for (String id : liveIndexed.keySet()) {
            if (!present.contains(id)) {
                missing.add(id);
            }
        }
        if (!confirmDelete && !liveIndexed.isEmpty() && missing.size() > config.maxDeleteRatio() * liveIndexed.size()) {
            return IndexReport.deleteAborted(missing.size(), liveIndexed.size());
        }

        boolean rebuilding = false;
        if (existing.isEmpty()) {
            PortResult<Void> init = store.initMeta(meta);
            if (init instanceof PortResult.Failed<Void> f) {
                return IndexReport.storeOrMetaFailed("init_meta:" + f.error().text());
            }
        } else if (rebuild) {
            PortResult<Void> begin = store.beginRebuild(meta);
            if (begin instanceof PortResult.Failed<Void> f) {
                return IndexReport.storeOrMetaFailed("begin_rebuild:" + f.error().text());
            }
            rebuilding = true;
        }

        // 재색인 중에는 쓰기가 대기 세대로 가므로 비교 대상도 그쪽(비어 있다)이고, 지울 것도 없다(새로 쓰는 세대에 없다).
        Map<String, String> indexed = rebuilding ? Map.of() : liveIndexed;
        List<String> toDelete = rebuilding ? List.of() : missing;

        boolean published = false;
        boolean lockLost = false;
        try {
            Applied applied = apply(held, docs, skippedEmpty, indexed, toDelete);
            lockLost = applied.lockLost;
            IndexReport report = applied.report;
            if (rebuilding && !lockLost) {
                report = finishRebuild(held, report);
            }
            published = report.outcome() == Outcome.OK;
            return report;
        } finally {
            if (rebuilding && !published && !lockLost) {
                // 실패했거나 예외로 빠져나왔다. 대기 세대를 버려 기존 색인을 지킨다(이미 처리했으면 무동작).
                // 잠금을 잃었다면 대기 세대가 이미 다른 실행의 것일 수 있어 건드리지 않는다.
                PortResult<Void> r = store.abortRebuild();
                if (r instanceof PortResult.Failed<Void> f) {
                    log.error("대기 세대를 버리지 못했다(다음 실행이 정리한다) error={}", f.error().text());
                }
            }
        }
    }

    private IndexReport finishRebuild(IndexLock.Handle held, IndexReport report) {
        if (report.outcome() != Outcome.OK) {
            PortResult<Void> r = store.abortRebuild();
            String note = r instanceof PortResult.Failed<Void> f
                    ? "대기 세대를 버리지 못했다(다음 실행이 정리한다): " + f.error().text()
                    : "대기 세대를 버렸다. 기존 색인은 그대로다";
            Outcome out = report.outcome() == Outcome.PARTIAL_FAILURE ? Outcome.REBUILD_ABORTED : report.outcome();
            return new IndexReport(out, report.added(), report.updated(), report.unchanged(), report.deleted(),
                    report.skippedEmpty(), report.failed(), report.detail().isEmpty() ? note : report.detail() + "; " + note);
        }
        if (!held.isHeld()) {
            return IndexReport.storeOrMetaFailed("lock_lost_before_commit");
        }
        PortResult<Void> commit = store.commitRebuild();
        if (commit instanceof PortResult.Failed<Void> f) {
            PortResult<Void> r = store.abortRebuild();
            return IndexReport.storeOrMetaFailed("commit_rebuild:" + f.error().text()
                    + (r instanceof PortResult.Failed<Void> af ? "; abort_failed:" + af.error().text() : ""));
        }
        return report;
    }

    private record Applied(IndexReport report, boolean lockLost) {}

    private Applied apply(IndexLock.Handle held, List<SourceDocument> docs, int skippedEmpty, Map<String, String> indexed,
            List<String> toDelete) {
        int added = 0;
        int updated = 0;
        int unchanged = 0;
        List<FailedDocument> failed = new ArrayList<>();
        for (SourceDocument d : docs) {
            String key = documentKey(d);
            String stored = indexed.get(d.id());
            if (key.equals(stored)) {
                unchanged++;
                continue;
            }
            if (!held.isHeld()) {
                return new Applied(new IndexReport(Outcome.STORE_OR_META_FAILED, added, updated, unchanged, 0, skippedEmpty,
                        failed, "lock_lost"), true);
            }
            Optional<ErrorInfo> error = indexDocument(d, key);
            if (error.isPresent()) {
                failed.add(new FailedDocument(d.id(), error.get()));
                log.warn("문서 색인 실패 document_id={} error={}", d.id(), error.get().text());
            } else if (stored == null) {
                added++;
            } else {
                updated++;
            }
        }

        int deleted = 0;
        if (!toDelete.isEmpty()) {
            if (!held.isHeld()) {
                return new Applied(new IndexReport(Outcome.STORE_OR_META_FAILED, added, updated, unchanged, 0, skippedEmpty,
                        failed, "lock_lost"), true);
            }
            PortResult<Void> del = store.deleteDocuments(toDelete);
            if (del instanceof PortResult.Failed<Void> f) {
                return new Applied(new IndexReport(Outcome.STORE_OR_META_FAILED, added, updated, unchanged, 0, skippedEmpty,
                        failed, "delete:" + f.error().text()), false);
            }
            deleted = toDelete.size();
        }
        Outcome outcome = failed.isEmpty() ? Outcome.OK : Outcome.PARTIAL_FAILURE;
        return new Applied(new IndexReport(outcome, added, updated, unchanged, deleted, skippedEmpty, failed, ""), false);
    }

    /** @return 실패하면 오류. 한 문서의 청크가 하나라도 임베딩에 실패하면 그 문서는 건드리지 않는다. */
    private Optional<ErrorInfo> indexDocument(SourceDocument d, String key) {
        List<String> texts = chunker.chunk(d.content());
        List<DocumentChunk> chunks = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i++) {
            EmbeddingResult r = embedChunk(texts.get(i));
            if (r instanceof EmbeddingResult.Success s) {
                float[] v = s.vector();
                if (v.length != config.dimension()) {
                    return Optional.of(ErrorInfo.of(ErrorCode.EMBEDDING_DIMENSION_MISMATCH,
                            "got=" + v.length + " expected=" + config.dimension()));
                }
                chunks.add(new DocumentChunk(i, texts.get(i), v));
            } else if (r instanceof EmbeddingResult.TimedOut) {
                return Optional.of(ErrorInfo.of(ErrorCode.EMBEDDING_TIMEOUT));
            } else {
                return Optional.of(((EmbeddingResult.Failed) r).error());
            }
        }
        PortResult<Void> stored = store.replaceDocument(d.id(), d.title(), key, chunks);
        if (stored instanceof PortResult.Failed<Void> f) {
            return Optional.of(f.error());
        }
        return Optional.empty();
    }

    private EmbeddingResult embedChunk(String text) {
        EmbeddingResult last = null;
        for (int attempt = 1; attempt <= config.embedAttempts(); attempt++) {
            last = embedding.embed(text, config.embedTimeoutMs());
            boolean retryable = last instanceof EmbeddingResult.TimedOut
                    || (last instanceof EmbeddingResult.Failed f && f.retryable());
            if (last instanceof EmbeddingResult.Success || !retryable) {
                return last;
            }
        }
        return last;
    }

    /** 문서별 증분 키: 내용 해시 + 청킹 설정 + 모델·차원. 하나라도 바뀌면 다시 색인한다. */
    String documentKey(SourceDocument d) {
        return sha256(d.contentHash() + "|" + chunkingHash + "|" + meta.modelId() + "|" + meta.dimension());
    }

    static String sha256(String s) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e); // 모든 JDK가 SHA-256을 제공한다
        }
    }
}
