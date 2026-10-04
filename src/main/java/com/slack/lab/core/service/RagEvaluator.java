package com.slack.lab.core.service;

import com.slack.lab.core.model.DocumentHit;
import com.slack.lab.core.model.ErrorInfo;
import com.slack.lab.core.model.EvalQuestion;
import com.slack.lab.core.model.EvalReport;
import com.slack.lab.core.model.EvalReport.RankedDoc;
import com.slack.lab.core.model.EvalReport.Row;
import com.slack.lab.core.model.EvalReport.SetResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 검색 품질을 LLM 없이 재현 가능하게 잰다(PRD 3단계 품질 기준). 운영과 같은 {@link RetrievalService}(질의 추출·임베딩·검색·임계값·
 * 문맥 상한)를 쓰므로 "평가에서 맞았는데 운영에서 다르다"가 생기지 않는다.
 *
 * <ul>
 *   <li>hit@K: 정답이 있는 질문에서 정답 문서가 임계값 적용 <b>전</b> 순위의 문서 K개 안에 있는가(검색 자체의 품질)
 *   <li>근거 미주입: 정답이 없는 질문에서 임계값을 거친 뒤 주입된 조각이 0건인가(관련 없는 문서를 억지로 끼우지 않는가)
 *   <li>정답 외 추가 주입: 정답이 있는 질문에서 정답이 아닌 문서가 함께 주입된 건수(임계값이 느슨하다는 신호, 판정에는 안 쓴다)
 * </ul>
 */
public class RagEvaluator {

    private static final long BUDGET_MS = 60_000;
    private static final int WARMUP_ATTEMPTS = 6;

    private final RetrievalService retrieval;
    private final int hitK;

    public RagEvaluator(RetrievalService retrieval, int hitK) {
        if (hitK < 1) {
            throw new IllegalArgumentException("hitK >= 1");
        }
        this.retrieval = retrieval;
        this.hitK = hitK;
    }

    public EvalReport evaluate(List<EvalQuestion> questions) {
        warmUp();
        Map<String, List<EvalQuestion>> bySet = new LinkedHashMap<>();
        for (EvalQuestion q : questions) {
            bySet.computeIfAbsent(q.set(), k -> new ArrayList<>()).add(q);
        }
        List<SetResult> results = new ArrayList<>();
        for (var e : bySet.entrySet()) {
            results.add(evaluateSet(e.getKey(), e.getValue()));
        }
        return new EvalReport(results);
    }

    /**
     * 첫 질문이 모델 콜드 적재(M28 실측: 채팅 모델이 올라간 상태에서 bge-m3 첫 호출 23초)로 검색 불가가 되어 판정이 어긋나지
     * 않게, 순위 호출이 성공할 때까지 몇 번 데운다. 검색 기한(5초)에서 취소돼도 Ollama의 적재는 이어지므로 다음 시도가 빨라진다.
     */
    private void warmUp() {
        for (int i = 0; i < WARMUP_ATTEMPTS; i++) {
            if (retrieval.rank("warm up", BUDGET_MS, 1) instanceof RetrievalService.Ranking.Ranked) {
                return;
            }
        }
    }

    private SetResult evaluateSet(String set, List<EvalQuestion> questions) {
        int answerable = 0;
        int hits = 0;
        int none = 0;
        int noInjected = 0;
        int unavailable = 0;
        int extra = 0;
        List<Row> rows = new ArrayList<>();
        for (EvalQuestion q : questions) {
            // hit@K는 운영 topK와 무관하게 청크를 충분히 가져와 문서 K개로 센다(청크가 겹쳐 문서가 K개 미만이 되는 일을 막는다).
            // 주입은 운영과 같게 앞의 topK 조각만 임계값·문맥 상한에 통과시킨다.
            int fetch = Math.max(retrieval.topK(), hitK * 3);
            RetrievalService.Ranking ranking = retrieval.rank(q.promptText(), BUDGET_MS, fetch);
            if (ranking instanceof RetrievalService.Ranking.Unavailable u) {
                unavailable++;
                if (q.answerable()) {
                    answerable++;
                } else {
                    none++;
                }
                rows.add(new Row(q, List.of(), List.of(), false, false, u.error()));
                continue;
            }
            List<DocumentHit> raw = ((RetrievalService.Ranking.Ranked) ranking).hits();
            List<RankedDoc> ranked = distinctDocs(raw);
            List<String> topK = ranked.stream().limit(hitK).map(RankedDoc::documentId).toList();
            List<String> injected = distinctIds(retrieval.select(raw.subList(0, Math.min(retrieval.topK(), raw.size()))));
            boolean hit = q.answerable() && q.expected().stream().anyMatch(topK::contains);
            boolean noInjection = !q.answerable() && injected.isEmpty();
            if (q.answerable()) {
                answerable++;
                if (hit) {
                    hits++;
                }
                extra += (int) injected.stream().filter(id -> !q.expected().contains(id)).count();
            } else {
                none++;
                if (noInjection) {
                    noInjected++;
                }
            }
            rows.add(new Row(q, ranked, injected, hit, noInjection, (ErrorInfo) null));
        }
        return new SetResult(set, answerable, hits, none, noInjected, unavailable, extra, rows);
    }

    /** 청크 순위를 문서 순위로: 문서마다 가장 높은 점수를 남기고 처음 등장한 순서를 유지한다. */
    private static List<RankedDoc> distinctDocs(List<DocumentHit> hits) {
        Map<String, Double> best = new LinkedHashMap<>();
        for (DocumentHit h : hits) {
            if (h != null && h.documentId() != null) {
                best.merge(h.documentId(), h.score(), Math::max);
            }
        }
        return best.entrySet().stream().map(e -> new RankedDoc(e.getKey(), e.getValue())).collect(Collectors.toList());
    }

    private static List<String> distinctIds(List<DocumentHit> hits) {
        Set<String> ids = new LinkedHashSet<>();
        hits.forEach(h -> ids.add(h.documentId()));
        return List.copyOf(ids);
    }

    /**
     * P2-4 답변 쌍(RAG 끔/켬) 사람 비교 기록용 빈 표. 자동 판정이 어려운 "근거 활용"과 "근거가 없으면 부족하다고 안내하는가"는
     * 사람이 채운다(로컬 모델의 표현 변동 때문에 문자열 검사로 채점하지 않는다).
     */
    public static String pairsTemplate(List<EvalQuestion> questions) {
        StringBuilder sb = new StringBuilder("| 질문 ID | 종류 | 정답 문서 | RAG 끔 답변 | RAG 켬 답변 | 참고 문서 목록 | 근거 활용(O/X/해당없음) | 근거 없음 안내(O/X/해당없음) |\n")
                .append("|---|---|---|---|---|---|---|---|\n");
        for (EvalQuestion q : questions) {
            sb.append("| ").append(q.id()).append(" | ").append(q.kind().name().toLowerCase()).append(" | ")
                    .append(q.expected().isEmpty() ? "(없음)" : String.join(", ", q.expected())).append(" |  |  |  |  |  |\n");
        }
        return sb.toString();
    }
}
