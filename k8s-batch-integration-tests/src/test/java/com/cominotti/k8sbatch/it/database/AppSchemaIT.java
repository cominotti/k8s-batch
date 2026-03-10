package com.cominotti.k8sbatch.it.database;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppSchemaIT extends AbstractIntegrationTest {

    @Test
    void shouldCreateAppDataTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'target_records'",
                String.class);

        assertThat(tables).containsExactly("target_records");
    }

    @Test
    void shouldApplyAllMigrations() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldEnforceNotNullConstraint() {
        assertThatThrownBy(() ->
                jdbcTemplate.execute(
                        "INSERT INTO target_records (id, name) VALUES (99999, NULL)"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
