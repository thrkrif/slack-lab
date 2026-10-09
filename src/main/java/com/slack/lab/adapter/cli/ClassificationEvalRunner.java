package com.slack.lab.adapter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.core.model.ClassificationReport;
import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.model.ClassifyResult;
import com.slack.lab.core.model.RequestKind;
import com.slack.lab.core.port.RequestClassifier;
import com.slack.lab.core.service.ClassificationEvaluator;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code app.role=evaluator --classify-eval}일 때 분류 정확도와 지연을 잰다(PLAN 4단계 M33·M36). 옵션 {@code --eval.dir}
 * (기본 docs/rag-eval), {@code --eval.file}(기본 classification.json, 보강 세트는 classification-tuning-extra.json), {@code --eval.set=tuning|final|all}(기본 tuning — 프롬프트를 조정하는 동안 final을 보지 않게 final은
 * 명시해야 돌린다). 지연은 워밍업 1회 뒤 모든 호출을 센다(사후 제외 없음, C5 "웜" 정의).
 * 종료 코드: 0 합격(또는 final 미실행), 1 불합격, 2 입력 오류.
 */
@Component
@ConditionalOnRole(AppRole.EVALUATOR)
@ConditionalOnProperty(prefix = "classification", name = "enabled", havingValue = "true")
public class ClassificationEvalRunner implements ApplicationRunner {

    private static final long CALL_BUDGET_MS = 30_000;

    private final RequestClassifier classifier;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;

    ClassificationEvalRunner(RequestClassifier classifier, ObjectMapper mapper, ConfigurableApplicationContext context) {
        this.classifier = classifier;
        this.mapper = mapper;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("classify-eval")) {
            return;
        }
        int code = execute(args);
        System.out.flush();
        System.exit(SpringApplication.exit(context, () -> code));
    }

    private int execute(ApplicationArguments args) {
        List<String> dirs = args.getOptionValues("eval.dir");
        List<String> sets = args.getOptionValues("eval.set");
        List<String> files = args.getOptionValues("eval.file");
        String file = files == null || files.isEmpty() ? "classification.json" : files.get(0);
        String dir = dirs == null || dirs.isEmpty() ? "docs/rag-eval" : dirs.get(0);
        String set = sets == null || sets.isEmpty() ? ClassifyQuestion.TUNING : sets.get(0);
        List<ClassifyQuestion> questions;
        try {
            questions = ClassificationSetLoader.load(Path.of(dir, file), mapper);
        } catch (Exception e) {
            System.out.println("분류 평가 질문을 읽지 못했다: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return 2;
        }
        if (!set.equals("all")) {
            questions = questions.stream().filter(q -> q.set().equals(set)).toList();
        }
        if (questions.isEmpty()) {
            System.out.println("평가할 질문이 없다: --eval.set=" + set);
            return 2;
        }
        classifier.classify("워밍업", CALL_BUDGET_MS); // 첫 호출의 모델 적재를 판정 밖으로 둔다(이후 모든 호출은 센다)
        List<Long> latencies = new ArrayList<>();
        ClassificationReport report = new ClassificationEvaluator(text -> {
            ClassifyResult r = classifier.classify(text, CALL_BUDGET_MS);
            if (r instanceof ClassifyResult.Classified c) {
                latencies.add(c.elapsedMs());
                return c.kind();
            }
            latencies.add(((ClassifyResult.Failed) r).elapsedMs());
            return null;
        }).evaluate(questions);
        System.out.println(report.summary());
        System.out.println(latencySummary(latencies));
        boolean hasFinal = report.sets().stream().anyMatch(s -> s.set().equals(ClassifyQuestion.FINAL));
        return hasFinal && !report.finalPassed() ? 1 : 0;
    }

    /** nearest-rank 백분위. 표본이 적으면 p95가 최댓값과 같다는 점을 출력에 적는다. */
    static String latencySummary(List<Long> latencies) {
        if (latencies.isEmpty()) {
            return "분류 지연: 표본 없음";
        }
        List<Long> sorted = new ArrayList<>(latencies);
        Collections.sort(sorted);
        int n = sorted.size();
        long p50 = sorted.get((int) Math.ceil(n * 0.50) - 1);
        long p95 = sorted.get((int) Math.ceil(n * 0.95) - 1);
        long slow = sorted.stream().filter(v -> v > 2_000).count();
        return "분류 지연(워밍업 제외, 사후 제외 없음) n=" + n + " p50=" + p50 + "ms p95=" + p95 + "ms(nearest-rank) max="
                + sorted.get(n - 1) + "ms, 2초 초과 " + slow + "건";
    }
}
