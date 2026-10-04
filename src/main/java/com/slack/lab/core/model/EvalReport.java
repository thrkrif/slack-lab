package com.slack.lab.core.model;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 평가 결과. 합격 판정은 {@code final} 세트에만 한다(PRD 3단계 범위): 정답이 있는 질문 중 hit@3 비율 ≥ 20/24, 정답이 없는 질문 중
 * 근거를 주입하지 않은 비율 ≥ 5/6, 검색 불가 0건. 이 수치는 기능 검증·회귀 감지용이며 일반 검색 품질을 보장하지 않는다.
 */
public record EvalReport(List<SetResult> sets) {

    /** 합격 판정 규격(PRD 3단계): final은 정답 24 + 정답 없음 6, hit@3 ≥ 20/24, 근거 미주입 ≥ 5/6. 판정·검사·요약이 같은 값을 쓴다. */
    public static final int HIT_K = 3;
    public static final int FINAL_ANSWERABLE = 24;
    public static final int FINAL_UNANSWERABLE = 6;
    public static final int REQUIRED_HITS = 20;
    public static final int REQUIRED_NO_INJECTION = 5;

    /** 한 질문의 결과. {@code ranked}는 임계값 적용 전 순위(문서 ID와 최고 점수), {@code injected}는 임계값·문맥 상한 적용 후다. */
    public record Row(EvalQuestion question, List<RankedDoc> ranked, List<String> injected, boolean hit, boolean noInjection,
            ErrorInfo unavailable) {
        public boolean isUnavailable() {
            return unavailable != null;
        }
    }

    public record RankedDoc(String documentId, double score) {}

    public record SetResult(String set, int answerable, int hits, int none, int noInjected, int unavailable,
            int extraInjected, List<Row> rows) {

        public SetResult {
            rows = List.copyOf(rows);
        }

        /** 합격 조건. 정수로 비교한다(20/24와 5/6 경계가 부동소수점에 흔들리지 않게). */
        public boolean passes() {
            boolean hitOk = answerable == 0 || hits * FINAL_ANSWERABLE >= answerable * REQUIRED_HITS;
            boolean noneOk = none == 0 || noInjected * FINAL_UNANSWERABLE >= none * REQUIRED_NO_INJECTION;
            return answerable + none > 0 && hitOk && noneOk && unavailable == 0; // 빈 세트가 공허하게 합격하지 않는다
        }
    }

    public EvalReport {
        sets = List.copyOf(sets);
    }

    public SetResult set(String name) {
        return sets.stream().filter(s -> s.set().equals(name)).findFirst().orElse(null);
    }

    /** 최종 세트가 평가에 포함됐는가. 없으면 합격 판정을 하지 않는다. */
    public boolean hasFinal() {
        return set(EvalQuestion.FINAL) != null;
    }

    public boolean finalPassed() {
        SetResult f = set(EvalQuestion.FINAL);
        return f != null && f.passes();
    }

    public boolean anyUnavailable() {
        return sets.stream().anyMatch(s -> s.unavailable() > 0);
    }

    /** 사람이 읽는 요약. 문서 내용은 없다(질문 ID, 문서 ID, 점수만). */
    public String summary() {
        StringBuilder sb = new StringBuilder();
        for (SetResult s : sets) {
            boolean judged = EvalQuestion.FINAL.equals(s.set());
            sb.append("== ").append(s.set()).append(judged ? " (합격 판정)" : " (조정용, 판정 아님)").append('\n');
            sb.append(String.format("hit@" + HIT_K + " %d/%d  근거 미주입 %d/%d  검색 불가 %d  정답 질문에서 정답 외 문서 추가 주입 %d건%n", s.hits(),
                    s.answerable(), s.noInjected(), s.none(), s.unavailable(), s.extraInjected()));
            for (Row r : s.rows()) {
                sb.append(String.format("  %-6s %-7s %s expected=%s ranked=%s injected=%s%n", r.question().id(),
                        r.question().kind().name().toLowerCase(), verdict(r), r.question().expected(), ranked(r.ranked()),
                        r.injected()));
            }
            if (judged) {
                sb.append("판정: ").append(s.passes() ? "합격" : "불합격").append(String.format(" (hit@%d ≥ %d/%d, 근거 미주입 ≥ %d/%d, 검색 불가 0)%n",
                        HIT_K, REQUIRED_HITS, FINAL_ANSWERABLE, REQUIRED_NO_INJECTION, FINAL_UNANSWERABLE));
            }
        }
        return sb.toString();
    }

    private static String verdict(Row r) {
        if (r.isUnavailable()) {
            return "UNAVAILABLE(" + r.unavailable().text() + ")";
        }
        return r.question().answerable() ? (r.hit() ? "HIT " : "MISS") : (r.noInjection() ? "NONE-OK" : "NONE-INJECTED");
    }

    private static String ranked(List<RankedDoc> docs) {
        return docs.stream().map(d -> d.documentId() + ":" + String.format("%.2f", d.score())).collect(Collectors.joining(",", "[", "]"));
    }
}
