package com.slack.lab.core.service;

import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.model.DocumentHit;
import com.slack.lab.core.model.RequestKind;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 분류를 끈 3단계 흐름에서 장애가 아닌 질문(단순·정보 부족)에 문서가 얼마나 주입되는지 잰다(PLAN 4단계 C3 기준선). LLM 없이
 * 운영과 같은 {@link RetrievalService}(질의 추출·임베딩·검색·임계값·문맥 상한)만 쓴다. 이 기준선이 너무 낮으면(주입되는 질문이
 * 적으면) 분류가 흡수할 게 없어 C3이 아무것도 증명하지 못하므로 합격선에서 빼고 보고 항목으로 내린다.
 */
public class InjectionBaseline {

    /** C3을 합격선에 두려면 final의 단순+정보 부족 40문항 중 이만큼 이상에서 문서가 주입돼야 한다(PLAN M32). */
    public static final int MIN_INJECTED_QUESTIONS = 15;

    private static final long BUDGET_MS = 60_000;

    public record Row(ClassifyQuestion question, List<String> injectedDocs, boolean unavailable) {
        public boolean injected() {
            return !injectedDocs.isEmpty();
        }
    }

    private final RetrievalService retrieval;

    public InjectionBaseline(RetrievalService retrieval) {
        this.retrieval = retrieval;
    }

    /** 장애가 아닌 질문만 던진다(장애 질문은 분류 on에서도 주입되므로 흡수 대상이 아니다). */
    public List<Row> measure(List<ClassifyQuestion> questions) {
        List<Row> rows = new ArrayList<>();
        for (ClassifyQuestion q : questions) {
            if (q.expected() == RequestKind.TROUBLE) {
                continue;
            }
            RetrievalService.Ranking ranking = retrieval.rank(q.text(), BUDGET_MS, retrieval.topK());
            if (ranking instanceof RetrievalService.Ranking.Ranked ranked) {
                List<DocumentHit> selected = retrieval.select(ranked.hits());
                LinkedHashSet<String> ids = new LinkedHashSet<>();
                selected.forEach(h -> ids.add(h.documentId()));
                rows.add(new Row(q, List.copyOf(ids), false));
            } else {
                rows.add(new Row(q, List.of(), true));
            }
        }
        return rows;
    }

    public static long injectedCount(List<Row> rows, String set) {
        return rows.stream().filter(r -> r.question().set().equals(set) && r.injected()).count();
    }

    public static boolean anyUnavailable(List<Row> rows) {
        return rows.stream().anyMatch(Row::unavailable);
    }

    /** final 세트에서 기준선이 충분한가. 검색 불가가 있으면 판단하지 않는다(false). */
    public static boolean c3Included(List<Row> rows) {
        return !anyUnavailable(rows) && injectedCount(rows, ClassifyQuestion.FINAL) >= MIN_INJECTED_QUESTIONS;
    }

    public static String summary(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        for (String set : List.of(ClassifyQuestion.TUNING, ClassifyQuestion.FINAL)) {
            List<Row> in = rows.stream().filter(r -> r.question().set().equals(set)).toList();
            if (in.isEmpty()) {
                continue;
            }
            sb.append("[").append(set).append("] 단순+정보 부족 ").append(in.size()).append("문항 중 문서 주입 ")
                    .append(injectedCount(rows, set)).append("문항\n");
            for (RequestKind k : List.of(RequestKind.SIMPLE, RequestKind.NEEDS_INFO)) {
                long n = in.stream().filter(r -> r.question().expected() == k).count();
                long inj = in.stream().filter(r -> r.question().expected() == k && r.injected()).count();
                sb.append("  ").append(k).append(": ").append(inj).append("/").append(n).append("\n");
            }
            in.forEach(r -> sb.append("    ").append(r.question().id()).append(r.unavailable() ? " 검색불가" : r.injected()
                    ? " 주입 " + r.injectedDocs() : " 주입 없음").append(" :: ").append(r.question().text()).append("\n"));
        }
        boolean unavailable = anyUnavailable(rows);
        sb.append("C3 기준선 판정: final 주입 문항 ").append(injectedCount(rows, ClassifyQuestion.FINAL)).append("/")
                .append(MIN_INJECTED_QUESTIONS).append(" 이상 필요 → ")
                .append(unavailable ? "판단 불가(검색 불가 있음)"
                        : c3Included(rows) ? "C3을 합격선에 포함" : "C3을 합격선에서 빼고 보고 항목으로 내림").append("\n");
        return sb.toString();
    }
}
