// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.infra;

import com.cominotti.k8sbatch.it.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Smoke test for MySQL connectivity via the Testcontainers {@code @ServiceConnection}. */
class MysqlConnectivityIT extends AbstractIntegrationTest {

    @Test
    void shouldConnectToMysql() {
        Integer result = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
        assertThat(result).isEqualTo(1);
    }

    @Test
    void shouldUseCorrectDatabase() {
        String database = jdbcTemplate.queryForObject("SELECT DATABASE()", String.class);
        assertThat(database).isEqualTo("k8sbatch");
    }

    @Test
    void shouldHaveExpectedMysqlVersion() {
        String version = jdbcTemplate.queryForObject("SELECT VERSION()", String.class);
        // Must match TestContainerImages.MYSQL_IMAGE version
        assertThat(version).startsWith("8.0");
    }
}
