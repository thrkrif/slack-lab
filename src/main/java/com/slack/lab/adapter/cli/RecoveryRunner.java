package com.slack.lab.adapter.cli;

import com.slack.lab.core.service.RecoveryService;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.ConditionalOnRole;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.stereotype.Component;

/**
 * {@code app.role=recovery}일 때 명령줄 인자(옵션이 아닌 인자)를 복구 명령으로 실행하고 종료한다. 웹 포트가 없는
 * 일회성 CLI다(RoleWebTypePostProcessor) — 공개 엔드포인트가 될 수 없다.
 */
@Component
@ConditionalOnRole(AppRole.RECOVERY)
class RecoveryRunner implements ApplicationRunner {

    private final RecoveryService service;
    private final ConfigurableApplicationContext context;

    // 컨텍스트만 띄워 보는 테스트가 JVM을 죽이지 않게 끌 수 있다. 운영(CLI) 기본값은 항상 종료다.
    private final boolean exitAfterRun;

    RecoveryRunner(RecoveryService service, ConfigurableApplicationContext context,
            @Value("${recovery.exit-after-run:true}") boolean exitAfterRun) {
        this.service = service;
        this.context = context;
        this.exitAfterRun = exitAfterRun;
    }

    @Override
    public void run(ApplicationArguments args) {
        int code = service.execute(args.getNonOptionArgs(), System.out);
        System.out.flush();
        if (exitAfterRun) {
            System.exit(SpringApplication.exit(context, () -> code));
        }
    }
}
