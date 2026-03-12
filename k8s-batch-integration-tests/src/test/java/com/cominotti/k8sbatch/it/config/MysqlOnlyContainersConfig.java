// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Test configuration for standalone tests that only need MySQL (no Kafka/Redpanda). Skips
 * Redpanda startup entirely, significantly reducing test infrastructure time.
 */
@TestConfiguration(proxyBeanMethods = false)
public class MysqlOnlyContainersConfig {

    private static final Logger log = LoggerFactory.getLogger(MysqlOnlyContainersConfig.class);

    static {
        ContainerHolder.startMysqlOnly();
    }

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        log.debug("Providing MySQL-only container (standalone mode)");
        return ContainerHolder.MYSQL;
    }
}
