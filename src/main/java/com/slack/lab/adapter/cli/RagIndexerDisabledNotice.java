package com.slack.lab.adapter.cli;

import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/** RAG가 꺼진 채 색인 역할로 띄우면 아무 일도 안 하고 끝나지 않게 이유를 알리고 실패로 종료한다(조용한 실패 금지). */
@Component
@ConditionalOnRole(AppRole.INDEXER)
@ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "false", matchIfMissing = true)
class RagIndexerDisabledNotice implements ApplicationRunner {

    private final ConfigurableApplicationContext context;

    RagIndexerDisabledNotice(ConfigurableApplicationContext context) {
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        System.err.println("색인을 하지 않았다: rag.enabled=true(RAG_ENABLED=true)와 rag.embedding-model·dimension이 필요하다");
        System.exit(SpringApplication.exit(context, () -> 2));
    }
}
