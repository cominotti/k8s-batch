// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.database;

import com.cominotti.k8sbatch.K8sBatchApplication;
import com.cominotti.k8sbatch.it.config.OracleOnlyContainersConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that Liquibase-managed application schema is correctly created on Oracle DB.
 * Uses Testcontainers Oracle Free to verify multi-database compatibility.
 */
@SpringBootTest(classes = K8sBatchApplication.class)
@Import(OracleOnlyContainersConfig.class)
@ActiveProfiles({"integration-test", "standalone"})
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class OracleSchemaIT {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void shouldCreateAllApplicationTables() {
        List<String> tables = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME FROM USER_TABLES WHERE TABLE_NAME IN "
                        + "('TARGET_RECORDS', 'ENRICHED_TRANSACTIONS', 'RULES_POC_ENRICHED_TRANSACTIONS') "
                        + "ORDER BY TABLE_NAME",
                String.class);

        assertThat(tables).containsExactlyInAnyOrder(
                "ENRICHED_TRANSACTIONS",
                "RULES_POC_ENRICHED_TRANSACTIONS",
                "TARGET_RECORDS"
        );
    }

    @Test
    void shouldApplyAllMigrations() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG WHERE EXECTYPE = 'EXECUTED'",
                Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(3);
    }
}
