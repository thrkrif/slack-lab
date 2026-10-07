package com.slack.lab.core.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.slack.lab.core.model.ClassificationReport;
import com.slack.lab.core.model.ClassificationReport.SetResult;
import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.model.RequestKind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

// C2 합격선(51/60, 장애 재현율 18/20, NEEDS_INFO 오판 ≤ 2)의 정수 경계를 가짜 분류기로 고정한다.
class ClassificationEvaluatorTest {

    /** final 60: 장애 20 · 단순 20 · 정보 부족 20. 질문 텍스트가 곧 ID라 가짜 분류기가 답을 조작할 수 있다. */
    private static List<ClassifyQuestion> finalSet() {
        List<ClassifyQuestion> qs = new ArrayList<>();
        for (RequestKind k : RequestKind.values()) {
            for (int i = 0; i < 20; i++) {
                qs.add(new ClassifyQuestion(k + "-" + i, ClassifyQuestion.FINAL, k, k + "-" + i));
            }
        }
        return qs;
    }

    private static SetResult run(Map<String, RequestKind> overrides, List<ClassifyQuestion> qs) {
        // 기본은 정답을 그대로 맞히는 분류기. overrides의 값이 null이면 분류 실패.
        Map<String, RequestKind> answers = new HashMap<>();
        qs.forEach(q -> answers.put(q.text(), q.expected()));
        answers.putAll(overrides);
        ClassificationReport r = new ClassificationEvaluator(answers::get).evaluate(qs);
        return r.sets().get(0);
    }

    @Test
    void 전부_맞히면_합격() {
        SetResult r = run(Map.of(), finalSet());
        assertThat(r.correct()).isEqualTo(60);
        assertThat(r.passed()).isTrue();
    }

    @Test
    void 전체_정확도_51은_합격_50은_불합격() {
        // 정보 부족 질문을 단순으로 틀리게 한다 — 장애 재현율·NEEDS_INFO 오판에는 영향이 없다.
        Map<String, RequestKind> nine = new HashMap<>();
        for (int i = 0; i < 9; i++) {
            nine.put("NEEDS_INFO-" + i, RequestKind.SIMPLE);
        }
        assertThat(run(nine, finalSet()).correct()).isEqualTo(51);
        assertThat(run(nine, finalSet()).passed()).isTrue();
        nine.put("NEEDS_INFO-9", RequestKind.SIMPLE);
        SetResult fifty = run(nine, finalSet());
        assertThat(fifty.correct()).isEqualTo(50);
        assertThat(fifty.passed()).isFalse();
    }

    @Test
    void 장애_재현율_18은_합격_17은_불합격() {
        Map<String, RequestKind> two = new HashMap<>();
        two.put("TROUBLE-0", RequestKind.SIMPLE);
        two.put("TROUBLE-1", RequestKind.SIMPLE);
        assertThat(run(two, finalSet()).passed()).isTrue();
        two.put("TROUBLE-2", RequestKind.SIMPLE);
        SetResult r = run(two, finalSet());
        assertThat(r.troubleCorrect()).isEqualTo(17);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void 정보부족_오판은_2건까지_합격_3건은_불합격() {
        Map<String, RequestKind> m = new HashMap<>();
        m.put("SIMPLE-0", RequestKind.NEEDS_INFO);
        m.put("SIMPLE-1", RequestKind.NEEDS_INFO);
        assertThat(run(m, finalSet()).passed()).isTrue();
        m.put("TROUBLE-0", RequestKind.NEEDS_INFO);
        SetResult r = run(m, finalSet());
        assertThat(r.needsInfoFalse()).isEqualTo(3);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void 분류_실패는_오답이고_장애_재현율에도_점수를_주지_않는다() {
        Map<String, RequestKind> failures = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            failures.put("TROUBLE-" + i, null);
        }
        SetResult r = run(failures, finalSet());
        assertThat(r.failed()).isEqualTo(3);
        assertThat(r.troubleCorrect()).isEqualTo(17);
        assertThat(r.correct()).isEqualTo(57);
        assertThat(r.passed()).isFalse();
    }

    @Test
    void 혼동_행렬은_정답과_예측을_센다() {
        Map<String, RequestKind> m = Map.of("SIMPLE-0", RequestKind.TROUBLE, "SIMPLE-1", RequestKind.TROUBLE);
        SetResult r = run(m, finalSet());
        assertThat(r.confusion().get(RequestKind.SIMPLE).get(RequestKind.TROUBLE)).isEqualTo(2);
        assertThat(r.confusion().get(RequestKind.SIMPLE).get(RequestKind.SIMPLE)).isEqualTo(18);
    }

    @Test
    void tuning_세트이거나_60문항이_아니면_판정하지_않는다() {
        List<ClassifyQuestion> tuning = new ArrayList<>();
        for (ClassifyQuestion q : finalSet()) {
            tuning.add(new ClassifyQuestion(q.id(), ClassifyQuestion.TUNING, q.expected(), q.text()));
        }
        assertThat(run(Map.of(), tuning).judgeable()).isFalse();
        assertThat(run(Map.of(), finalSet().subList(0, 59)).judgeable()).isFalse();
    }
}
