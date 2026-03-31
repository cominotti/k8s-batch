// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.database;

import com.cominotti.k8sbatch.crud.it.AbstractCrudIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that Liquibase migrations create the expected CRUD tables and that Hibernate's
 * {@code ddl-auto=validate} passes (entity mappings match the schema).
 */
class CrudSchemaIT extends AbstractCrudIntegrationTest {

    private static final String CUSTOMERS_TABLE = "customers";
    private static final String ACCOUNTS_TABLE = "accounts";
    private static final String CUSTOMER_SEQUENCE_TABLE = "customer_sequence";
    private static final String ACCOUNT_SEQUENCE_TABLE = "account_sequence";

    @Test
    void shouldCreateCustomersTable() {
        assertThat(tableExists(CUSTOMERS_TABLE)).isTrue();
    }

    @Test
    void shouldCreateAccountsTable() {
        assertThat(tableExists(ACCOUNTS_TABLE)).isTrue();
    }

    @Test
    void shouldCreateSequenceEmulationTables() {
        assertThat(tableExists(CUSTOMER_SEQUENCE_TABLE)).isTrue();
        assertThat(tableExists(ACCOUNT_SEQUENCE_TABLE)).isTrue();
    }

    @Test
    void shouldHaveAllLiquibaseMigrations() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM DATABASECHANGELOG", Integer.class);
        assertThat(count).isGreaterThanOrEqualTo(6);
    }

    private boolean tableExists(String tableName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?",
                Integer.class, tableName);
        return count != null && count == 1;
    }
}
