package com.slack.lab.core.service;

import com.slack.lab.core.model.ClassificationReport;
import com.slack.lab.core.model.ClassificationReport.Row;
import com.slack.lab.core.model.ClassificationReport.SetResult;
import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.model.RequestKind;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 분류 정확도를 LLM 없이도 재현 가능하게 판정한다(PLAN 4단계 C2). 분류기는 질문 텍스트를 받아 종류를 돌려주는 함수이며,
 * 실패는 {@code null}로 돌려준다 — M33에서 {@code RequestClassifier} 포트가 이 함수 자리에 들어온다. 판정 규칙은
 * {@link ClassificationReport}에 고정돼 있어 평가 결과를 보고 기준을 바꿀 수 없다.
 */
public class ClassificationEvaluator {

    private final Function<String, RequestKind> classifier;

    public ClassificationEvaluator(Function<String, RequestKind> classifier) {
        this.classifier = classifier;
    }

    public ClassificationReport evaluate(List<ClassifyQuestion> questions) {
        Map<String, List<ClassifyQuestion>> bySet = new LinkedHashMap<>();
        for (ClassifyQuestion q : questions) {
            bySet.computeIfAbsent(q.set(), k -> new ArrayList<>()).add(q);
        }
        List<SetResult> results = new ArrayList<>();
        bySet.forEach((set, qs) -> results.add(evaluateSet(set, qs)));
        return new ClassificationReport(results);
    }

    private SetResult evaluateSet(String set, List<ClassifyQuestion> questions) {
        Map<RequestKind, Map<RequestKind, Integer>> confusion = new EnumMap<>(RequestKind.class);
        for (RequestKind k : RequestKind.values()) {
            confusion.put(k, new EnumMap<>(RequestKind.class));
        }
        List<Row> rows = new ArrayList<>();
        int correct = 0;
        int failed = 0;
        int troubleTotal = 0;
        int troubleCorrect = 0;
        int needsInfoFalse = 0;
        for (ClassifyQuestion q : questions) {
            RequestKind predicted = classifier.apply(q.text());
            rows.add(new Row(q, predicted));
            if (predicted == null) {
                failed++;
            } else {
                confusion.get(q.expected()).merge(predicted, 1, Integer::sum);
            }
            boolean ok = predicted == q.expected();
            if (ok) {
                correct++;
            }
            if (q.expected() == RequestKind.TROUBLE) {
                troubleTotal++;
                if (ok) {
                    troubleCorrect++;
                }
            }
            if (q.expected() != RequestKind.NEEDS_INFO && predicted == RequestKind.NEEDS_INFO) {
                needsInfoFalse++;
            }
        }
        return new SetResult(set, questions.size(), correct, failed, troubleTotal, troubleCorrect, needsInfoFalse,
                confusion, rows);
    }
}
