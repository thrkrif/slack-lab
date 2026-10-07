package com.slack.lab.core.model;

import java.util.List;
import java.util.Map;

/**
 * 분류 평가 결과. 합격선은 PLAN 4단계 C2로 사전 고정돼 있고, 분류 실패({@code predicted == null})는 오답으로 센다 —
 * 장애로 폴백해도 재현율에 점수를 주지 않기 위해서다(폴백 건수는 {@code failed}로 따로 센다).
 */
public record ClassificationReport(List<SetResult> sets) {

    public static final int FINAL_TOTAL = 60;
    public static final int MIN_CORRECT = 51;
    public static final int MIN_TROUBLE_RECALL = 18;
    public static final int MAX_NEEDS_INFO_FALSE = 2;

    public record Row(ClassifyQuestion question, RequestKind predicted) {
        public boolean correct() {
            return predicted == question.expected();
        }
    }

    /**
     * @param confusion 정답 → (예측 → 건수). 분류 실패는 예측 키 {@code null} 대신 {@code failed}에 센다
     * @param needsInfoFalse 정답이 장애·단순인데 NEEDS_INFO로 예측한 건수(봇이 답을 거부하는 방향의 오판)
     */
    public record SetResult(String set, int total, int correct, int failed, int troubleTotal, int troubleCorrect,
            int needsInfoFalse, Map<RequestKind, Map<RequestKind, Integer>> confusion, List<Row> rows) {

        /** final 세트가 60문항(장애 20)일 때만 판정한다. 아니면 비교 대상이 달라 판정하지 않는다. */
        public boolean judgeable() {
            return set.equals(ClassifyQuestion.FINAL) && total == FINAL_TOTAL;
        }

        public boolean passed() {
            return judgeable() && correct >= MIN_CORRECT && troubleCorrect >= MIN_TROUBLE_RECALL
                    && needsInfoFalse <= MAX_NEEDS_INFO_FALSE;
        }
    }

    /** final 세트 합격 여부. final이 없거나 판정 불가이면 false. */
    public boolean finalPassed() {
        return sets.stream().anyMatch(SetResult::passed);
    }

    /** 사람이 읽는 요약. 오답 행과 혼동 행렬을 함께 보여 줘 어떤 라벨이 어디로 새는지 보게 한다. */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (SetResult r : sets) {
            sb.append("[").append(r.set()).append("] 정확 ").append(r.correct()).append("/").append(r.total())
                    .append(", 분류 실패 ").append(r.failed()).append(", 장애 재현율 ").append(r.troubleCorrect()).append("/")
                    .append(r.troubleTotal()).append(", 정보 부족 오판(장애·단순→정보 부족) ").append(r.needsInfoFalse()).append("\n");
            for (RequestKind gold : RequestKind.values()) {
                sb.append("  정답 ").append(gold).append(" → ");
                for (RequestKind pred : RequestKind.values()) {
                    sb.append(pred).append(" ").append(r.confusion().get(gold).getOrDefault(pred, 0)).append("  ");
                }
                sb.append("\n");
            }
            for (Row row : r.rows()) {
                if (!row.correct()) {
                    sb.append("    오답 ").append(row.question().id()).append(" 정답=").append(row.question().expected())
                            .append(" 예측=").append(row.predicted() == null ? "실패" : row.predicted().name()).append(" :: ")
                            .append(row.question().text()).append("\n");
                }
            }
            if (r.judgeable()) {
                sb.append("  합격선(≥").append(MIN_CORRECT).append("/").append(FINAL_TOTAL).append(", 장애 재현율 ≥")
                        .append(MIN_TROUBLE_RECALL).append("/20, 정보 부족 오판 ≤").append(MAX_NEEDS_INFO_FALSE).append(") → ")
                        .append(r.passed() ? "합격" : "불합격").append("\n");
            } else if (r.set().equals(ClassifyQuestion.FINAL)) {
                sb.append("  final이 규격(").append(FINAL_TOTAL).append("문항)이 아니라 판정하지 않는다\n");
            }
        }
        return sb.toString();
    }
}
