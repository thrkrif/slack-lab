package com.slack.lab.adapter.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.AutoCheckProperties;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.PostgresProperties;
import com.slack.lab.config.StateProperties;
import com.slack.lab.core.port.BacklogProbe;
import com.slack.lab.core.port.EventRepublisher;
import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.port.ThreadLookup;
import com.slack.lab.core.service.BacklogReporter;
import com.slack.lab.core.service.RetryRelay;
import com.slack.lab.core.service.UnknownResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Postgres 작업 상태 저장소 배선(ADR-9, M22). 수신 서버는 상태 저장소가 필요
 * 없다(큐에 저장만 확인하고 200을 준다) — 워커·복구 역할만 DB에 연결한다. 전역 DataSource 자동 구성은 꺼져 있다.
 */
@Configuration
class PostgresConfig {

    @Bean(destroyMethod = "close")
    @ConditionalOnRole({AppRole.WORKER, AppRole.RECOVERY, AppRole.INDEXER, AppRole.ALL})
    HikariDataSource postgresDataSource(PostgresProperties props) {
        if (props.password().isBlank()) {
            throw new IllegalStateException("postgres.password(POSTGRES_PASSWORD)가 비어 있다. .env에 값을 넣는다.");
        }
        HikariConfig c = new HikariConfig();
        c.setJdbcUrl(props.url());
        c.setUsername(props.username());
        c.setPassword(props.password());
        c.setMaximumPoolSize(props.poolSize());
        c.setPoolName("slack-lab-pg");
        // DB가 죽어 있으면 기동 실패를 30초(기본값) 기다리지 않는다.
        c.setConnectionTimeout(5_000);
        HikariDataSource ds = new HikariDataSource(c);
        try {
            PostgresMigrations.migrate(ds);
        } catch (RuntimeException e) {
            ds.close(); // 빈 생성이 실패하면 destroyMethod가 불리지 않는다
            throw e;
        }
        return ds;
    }

    @Bean
    @ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
    PostgresProcessingStateStore processingStateStore(HikariDataSource ds, StateProperties state, ObjectMapper mapper) {
        return new PostgresProcessingStateStore(ds, state, mapper);
    }

    /** RAG 벡터 저장소(M25 어댑터, M26 배선). rag.enabled=true일 때만 만든다 — 꺼도 동작한다. */
    @Bean
    @ConditionalOnRole({AppRole.WORKER, AppRole.INDEXER, AppRole.ALL})
    @ConditionalOnProperty(prefix = "rag", name = "enabled", havingValue = "true")
    PostgresVectorStore vectorStore(HikariDataSource ds) {
        return new PostgresVectorStore(ds);
    }

    @Bean
    @ConditionalOnRole({AppRole.WORKER, AppRole.RECOVERY, AppRole.ALL})
    RecoveryStore recoveryStore(HikariDataSource ds, StateProperties state, ObjectMapper mapper) {
        return new PostgresRecoveryStore(ds, state, mapper);
    }

    @Bean
    @ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
    PostgresBacklogProbe postgresBacklogProbe(HikariDataSource ds) {
        return new PostgresBacklogProbe(ds);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
    BacklogReporter backlogReporter(java.util.List<BacklogProbe> probes) {
        return new BacklogReporter(probes);
    }

    @Bean
    @ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
    RetryRelay retryRelay(PostgresProcessingStateStore store, EventRepublisher republisher) {
        // 재투입 메시지가 선점되지 않은 채 이 시간이 지나면 유실로 보고 다시 넣는다. 재투입은 처리 시간이 아니라 "큐 대기 +
        // 선점까지"와 경쟁하므로 백로그가 이보다 길면 같은 세대가 반복 재투입될 수 있다 — 선점 결과표가 흡수해 정확성은
        // 유지되고 큐만 부푼다(알려진 한계).
        return new RetryRelay(store, republisher, 50, 120_000);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    @ConditionalOnRole({AppRole.WORKER, AppRole.ALL})
    PostgresMaintenance postgresMaintenance(RetryRelay relay, PostgresProcessingStateStore store,
            RecoveryStore recoveryStore, ThreadLookup threads, AutoCheckProperties autoCheck) {
        UnknownResolver resolver = autoCheck.enabled() ? new UnknownResolver(recoveryStore, threads, autoCheck.minAgeMs())
                : null;
        return new PostgresMaintenance(relay, resolver, store, autoCheck.intervalMs());
    }
}
