package com.slack.lab.adapter.postgres;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** 스키마 마이그레이션(Flyway). 전역 자동 구성은 끄고 어댑터가 필요할 때 직접 실행한다. */
public final class PostgresMigrations {

    private PostgresMigrations() {}

    public static void migrate(DataSource dataSource) {
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
    }
}
