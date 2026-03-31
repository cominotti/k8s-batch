// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;

/**
 * Test configuration that provides a MySQL Testcontainer with {@code @ServiceConnection}
 * for automatic DataSource wiring. Delegates container lifecycle to {@link CrudContainerHolder}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class CrudMysqlContainersConfig {

    static {
        CrudContainerHolder.startMysql();
    }

    /**
     * Exposes the shared MySQL container as a Spring bean with {@code @ServiceConnection},
     * which auto-configures {@code spring.datasource.*} properties.
     *
     * @return the MySQL container
     */
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return CrudContainerHolder.MYSQL;
    }
}
