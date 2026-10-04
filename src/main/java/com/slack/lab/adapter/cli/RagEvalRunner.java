package com.slack.lab.adapter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.RagIndexProperties;
import com.slack.lab.core.model.EvalQuestion;
import com.slack.lab.core.model.EvalReport;
import com.slack.lab.core.service.RagEvaluator;
import com.slack.lab.core.service.RetrievalService;
import java.nio.file.Path;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code app.role=indexer --eval}일 때 색인된 문서에 평가 질문을 던져 검색 품질을 잰다(PLAN M29). 옵션:
 * {@code --eval.dir}(기본 docs/rag-eval), {@code --eval.set=tuning|final|all}(기본 tuning — 임계값을 조정하는 동안 final 순위를 보지 않게 final은 명시해야 돌린다), {@code --eval.pairs}(답변 쌍 빈 표 출력).
 * 종료 코드: 0 합격(또는 최종 세트를 돌리지 않음), 1 불합격, 2 검색 불가가 있었거나 입력 오류.
 */
@Component
@ConditionalOnRole(AppRole.INDEXER)
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class RagEvalRunner implements ApplicationRunner {

    private final RetrievalService retrieval;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;
    private final boolean exitAfterRun;
    private volatile int lastExitCode = -1;

    /** 마지막 실행의 종료 코드(테스트가 JVM을 죽이지 않고 확인한다). 실행 전에는 -1. */
    public int lastExitCode() {
        return lastExitCode;
    }

    RagEvalRunner(RetrievalService retrieval, ObjectMapper mapper, ConfigurableApplicationContext context,
            RagIndexProperties props) {
        this.retrieval = retrieval;
        this.mapper = mapper;
        this.context = context;
        this.exitAfterRun = props.exitAfterRun();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("eval")) {
            return; // 색인 실행은 RagIndexRunner의 몫이다
        }
        int code = execute(args);
        lastExitCode = code;
        System.out.flush();
        if (exitAfterRun) {
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }

    private int execute(ApplicationArguments args) {
        String dir = option(args, "eval.dir", "docs/rag-eval");
        String set = option(args, "eval.set", EvalQuestion.TUNING);
        List<EvalQuestion> questions;
        try {
            questions = EvalSetLoader.load(Path.of(dir, "questions.json"), mapper);
        } catch (Exception e) {
            System.out.println("평가 질문을 읽지 못했다: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return 2;
        }
        if (!set.equals("all")) {
            questions = questions.stream().filter(q -> q.set().equals(set)).toList();
        }
        if (questions.isEmpty()) {
            System.out.println("평가할 질문이 없다: --eval.set=" + set);
            return 2;
        }
        // 합격 판정은 미리 고정한 규격(정답 24 + 정답 없음 6)의 final 세트에만 의미가 있다. 크기가 다른 final로는 판정하지 않는다.
        long finalAnswerable = questions.stream().filter(q -> q.set().equals(EvalQuestion.FINAL) && q.answerable()).count();
        long finalNone = questions.stream().filter(q -> q.set().equals(EvalQuestion.FINAL) && !q.answerable()).count();
        boolean hasFinal = questions.stream().anyMatch(q -> q.set().equals(EvalQuestion.FINAL));
        if (hasFinal && (finalAnswerable != EvalReport.FINAL_ANSWERABLE || finalNone != EvalReport.FINAL_UNANSWERABLE)) {
            System.out.println("final 세트가 규격(정답 " + EvalReport.FINAL_ANSWERABLE + " + 정답 없음 " + EvalReport.FINAL_UNANSWERABLE + ")이 아니라 판정하지 않는다: 정답 " + finalAnswerable + ", 정답 없음 " + finalNone);
            return 2;
        }
        if (args.containsOption("eval.pairs")) {
            System.out.println(RagEvaluator.pairsTemplate(questions));
            return 0;
        }
        EvalReport report = new RagEvaluator(retrieval, EvalReport.HIT_K).evaluate(questions);
        System.out.println(report.summary());
        if (report.anyUnavailable()) {
            System.out.println("검색 불가가 있어 판정할 수 없다(임베딩 서버·색인 상태를 확인한다)");
            return 2;
        }
        return report.hasFinal() && !report.finalPassed() ? 1 : 0;
    }

    private static String option(ApplicationArguments args, String name, String fallback) {
        List<String> v = args.getOptionValues(name);
        return v == null || v.isEmpty() ? fallback : v.get(0);
    }
}
