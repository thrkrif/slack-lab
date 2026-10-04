package com.slack.lab.adapter.cli;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.RagIndexProperties;
import com.slack.lab.core.model.IndexReport;
import com.slack.lab.core.service.IndexingService;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code app.role=indexer}일 때 색인을 한 번 실행하고 종료하는 일회성 CLI(웹 포트 없음). 옵션: {@code --rebuild}(모델·차원
 * 변경 전체 재색인), {@code --confirm-delete}(삭제 임계 비율 초과를 알고 진행). 결과는 stdout에, 종료 코드는 결과에 따른다.
 */
@Component
@ConditionalOnRole(AppRole.INDEXER)
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
class RagIndexRunner implements ApplicationRunner {

    private final IndexingService service;
    private final ConfigurableApplicationContext context;
    private final boolean exitAfterRun;

    RagIndexRunner(IndexingService service, ConfigurableApplicationContext context, RagIndexProperties props) {
        this.service = service;
        this.context = context;
        this.exitAfterRun = props.exitAfterRun();
    }

    @Override
    public void run(ApplicationArguments args) {
        IndexReport report = service.run(args.containsOption("rebuild"), args.containsOption("confirm-delete"));
        System.out.println(report.summary());
        System.out.flush();
        if (exitAfterRun) {
            System.exit(SpringApplication.exit(context, report::exitCode));
        }
    }
}
