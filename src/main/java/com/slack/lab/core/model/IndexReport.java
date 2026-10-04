package com.slack.lab.core.model;

import java.util.List;

/**
 * 색인 한 번의 결과. 문서 ID와 오류 분류만 담고 문서 내용은 담지 않는다. {@link #exitCode()}는 CLI 종료 코드다 — 부분 성공도
 * 0이 아니다. "색인됐다고 믿었는데 검색이 안 되는" 조용한 실패를 막으려는 것이다.
 */
public record IndexReport(Outcome outcome, int added, int updated, int unchanged, int deleted, int skippedEmpty,
        List<FailedDocument> failed, String detail) {

    public enum Outcome {
        OK(0),
        /** 일부 문서가 실패했다. 성공한 문서는 색인됐고 다음 실행이 실패분만 다시 시도한다. */
        PARTIAL_FAILURE(1),
        SOURCE_FAILED(2),
        /** 다른 색인이 진행 중이다. */
        LOCKED(3),
        /** 색인이 다른 모델·차원으로 만들어졌는데 전체 재색인을 요청하지 않았다, 또는 저장소 오류. */
        STORE_OR_META_FAILED(4),
        /** 삭제 대상이 임계 비율을 넘어 아무것도 쓰지 않고 멈췄다(경로 설정 실수 방어). */
        DELETE_ABORTED(5),
        /** 전체 재색인 중 실패가 있어 대기 세대를 버렸다. 기존 색인은 그대로다. */
        REBUILD_ABORTED(6);

        private final int exitCode;

        Outcome(int exitCode) {
            this.exitCode = exitCode;
        }

        public int exitCode() {
            return exitCode;
        }
    }

    public record FailedDocument(String documentId, ErrorInfo error) {}

    public IndexReport {
        failed = List.copyOf(failed);
        detail = detail == null ? "" : detail;
    }

    public int exitCode() {
        return outcome.exitCode();
    }

    static IndexReport stopped(Outcome outcome, String detail) {
        return new IndexReport(outcome, 0, 0, 0, 0, 0, List.of(), detail);
    }

    public static IndexReport sourceFailed(ErrorInfo error) {
        return stopped(Outcome.SOURCE_FAILED, error.text());
    }

    public static IndexReport locked() {
        return stopped(Outcome.LOCKED, "다른 색인이 진행 중이다");
    }

    public static IndexReport storeOrMetaFailed(String detail) {
        return stopped(Outcome.STORE_OR_META_FAILED, detail);
    }

    public static IndexReport deleteAborted(int toDelete, int indexed) {
        return stopped(Outcome.DELETE_ABORTED, "삭제 대상 " + toDelete + "/" + indexed
                + "건이 임계 비율을 넘어 중단했다. 출처 경로 설정을 확인하고, 의도했다면 --confirm-delete로 다시 실행한다");
    }

    /** 사람이 읽는 요약. 문서 내용은 없다. */
    public String summary() {
        StringBuilder sb = new StringBuilder("색인 결과 outcome=").append(outcome).append(" added=").append(added)
                .append(" updated=").append(updated).append(" unchanged=").append(unchanged).append(" deleted=")
                .append(deleted).append(" skipped_empty=").append(skippedEmpty).append(" failed=").append(failed.size());
        if (!detail.isEmpty()) {
            sb.append(" detail=").append(detail);
        }
        for (FailedDocument f : failed) {
            sb.append("\n  실패 ").append(f.documentId()).append(' ').append(f.error().text());
        }
        return sb.toString();
    }
}
