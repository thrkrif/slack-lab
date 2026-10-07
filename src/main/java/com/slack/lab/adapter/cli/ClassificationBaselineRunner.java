package com.slack.lab.adapter.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.RagIndexProperties;
import com.slack.lab.core.model.ClassifyQuestion;
import com.slack.lab.core.service.InjectionBaseline;
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
 * {@code app.role=indexer --classify-baseline}일 때 분류 off 주입 기준선을 잰다(PLAN 4단계 M32). 옵션 {@code --eval.dir}
 * (기본 docs/rag-eval), {@code --eval.set=tuning|final|all}(기본 all — 기준선은 검색 결과만 보며 프롬프트 튜닝에 쓰이지 않는다).
 * 종료 코드: 0 측정 완료, 2 검색 불가가 있었거나 입력 오류.
 */
@Component
@ConditionalOnRole(AppRole.INDEXER)
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
public class ClassificationBaselineRunner implements ApplicationRunner {

    private final RetrievalService retrieval;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext context;
    private final boolean exitAfterRun;

    ClassificationBaselineRunner(RetrievalService retrieval, ObjectMapper mapper, ConfigurableApplicationContext context,
            RagIndexProperties props) {
        this.retrieval = retrieval;
        this.mapper = mapper;
        this.context = context;
        this.exitAfterRun = props.exitAfterRun();
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!args.containsOption("classify-baseline")) {
            return;
        }
        int code = execute(args);
        System.out.flush();
        if (exitAfterRun) {
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }

    private int execute(ApplicationArguments args) {
        List<String> dirs = args.getOptionValues("eval.dir");
        List<String> sets = args.getOptionValues("eval.set");
        String dir = dirs == null || dirs.isEmpty() ? "docs/rag-eval" : dirs.get(0);
        String set = sets == null || sets.isEmpty() ? "all" : sets.get(0);
        List<ClassifyQuestion> questions;
        try {
            questions = ClassificationSetLoader.load(Path.of(dir, "classification.json"), mapper);
        } catch (Exception e) {
            System.out.println("분류 평가 질문을 읽지 못했다: " + e.getClass().getSimpleName() + " " + e.getMessage());
            return 2;
        }
        if (!set.equals("all")) {
            questions = questions.stream().filter(q -> q.set().equals(set)).toList();
        }
        var rows = new InjectionBaseline(retrieval).measure(questions);
        System.out.println(InjectionBaseline.summary(rows));
        return InjectionBaseline.anyUnavailable(rows) ? 2 : 0;
    }
}
