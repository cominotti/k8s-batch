// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.oracle.OracleContainer;

/**
 * Test configuration for Oracle-specific tests. Starts an Oracle Free container to verify
 * that Liquibase migrations are compatible with Oracle DB.
 */
@TestConfiguration(proxyBeanMethods = false)
public class OracleOnlyContainersConfig {

    private static final Logger log = LoggerFactory.getLogger(OracleOnlyContainersConfig.class);

    static {
        ContainerHolder.startOracleOnly();
    }

    /**
     * Provides the Oracle container as a {@link ServiceConnection} bean so Spring Boot
     * auto-configures the datasource URL, driver, and credentials for Oracle.
     *
     * @return the shared Oracle container instance
     */
    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        log.debug("Providing Oracle-only container");
        return ContainerHolder.getOracle();
    }
}
