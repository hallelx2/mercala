package com.mercala;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Flyway applied the baseline migration against a real Postgres and
 * that the required extensions (pgvector + pg_search) are installed.
 */
class PostgresMigrationTest extends AbstractIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void flywayBaselineAppliedSuccessfully() {
        Integer applied = jdbc.queryForObject(
                "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class);
        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void requiredExtensionsAreInstalled() {
        Integer extensions = jdbc.queryForObject(
                "SELECT count(*) FROM pg_extension WHERE extname IN ('vector', 'pg_search')",
                Integer.class);
        assertThat(extensions).isEqualTo(2);
    }
}
