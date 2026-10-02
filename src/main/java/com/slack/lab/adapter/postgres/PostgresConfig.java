package com.slack.lab.adapter.postgres;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.slack.lab.config.AppRole;
import com.slack.lab.config.AutoCheckProperties;
import com.slack.lab.config.ConditionalOnRole;
import com.slack.lab.config.PostgresProperties;
import com.slack.lab.config.StateProperties;
import com.slack.lab.core.port.EventRepublisher;
import com.slack.lab.core.port.RecoveryStore;
import com.slack.lab.core.port.ThreadLookup;
import com.slack.lab.core.service.RetryRelay;
import com.slack.lab.core.service.UnknownResolver;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Postgres 작업 상태 저장소 배선(ADR-9, M22). {@code state.backend=postgres}일 때만 켜진다. 수신 서버는 상태 저장소가 필요
 * 없다(큐에 저장만 확인하고 200을 준다) — 워커·복구 역할만 DB에 연결한다. 전역 DataSource 자동 구성은 꺼져 있다.
 */
@Configuration
@ConditionalOnProperty(name = "state.backend", havingValue = "postgres")
class PostgresConfig {

    /** 한쪽만 바꾼 반쯤 배선된 앱이 뜨지 않게 한다: Redis 큐의 전달 식별자와 Postgres 상태는 서로를 모른다. */
    @Bean
    ConsistencyGuard postgresConsistencyGuard(Environment env) {
        if (!"rabbitmq".equals(env.getProperty("queue.backend"))) {
            throw new IllegalStateException("state.backend=postgres는 queue.backend=rabbitmq와 함께 써야 한다.");
        }
        return new ConsistencyGuard();
    }

    static final class ConsistencyGuard {}

    @Bean(destroyMethod = "close")
    @ConditionalOnRole({AppRole.WORKER, AppRole.RECOVERY, AppRole.ALL})
    HikariDataSource postgresDataSource(PostgresProperties props) {
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

    @Bean
    @ConditionalOnRole({AppRole.WORKER, AppRole.RECOVERY, AppRole.ALL})
    RecoveryStore recoveryStore(HikariDataSource ds, StateProperties state, ObjectMapper mapper) {
        return new PostgresRecoveryStore(ds, state, mapper);
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
