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
}
