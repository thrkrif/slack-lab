package com.slack.lab.adapter.postgres;

import com.slack.lab.core.port.BacklogProbe;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** 재시도 대기·DLQ·복구 대상 건수. */
public class PostgresBacklogProbe implements BacklogProbe {

    private final JdbcTemplate jdbc;

    public PostgresBacklogProbe(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Map<String, Long> snapshot() {
        Map<String, Long> out = new LinkedHashMap<>();
        out.put("retry", count("SELECT count(*) FROM processing_state WHERE state = 'RETRY_WAIT'"));
        out.put("dlq", count("SELECT count(*) FROM preserved_input WHERE list_name = 'dlq'"));
        out.put("recovery", count("SELECT count(*) FROM preserved_input WHERE list_name = 'recovery'"));
        return out;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }
}
