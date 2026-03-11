// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * Holds shared Testcontainer instances with decoupled lifecycle.
 * <p>
 * Standalone tests call {@link #startMysqlOnly()} to avoid starting Redpanda.
 * Remote-partitioning tests call {@link #startAll()} to start both in parallel.
 */
final class ContainerHolder {

    private static final Logger log = LoggerFactory.getLogger(ContainerHolder.class);

    static final MySQLContainer MYSQL =
            new MySQLContainer(TestContainerImages.MYSQL_IMAGE)
                    .withDatabaseName("k8sbatch")
                    .withUsername("test")
                    .withPassword("test");

    static final RedpandaContainer REDPANDA =
            new RedpandaContainer(TestContainerImages.REDPANDA_IMAGE)
                    .withStartupTimeout(Duration.ofSeconds(120));

    private static volatile boolean mysqlStarted = false;
    private static volatile boolean redpandaStarted = false;

    static synchronized void startMysqlOnly() {
        if (mysqlStarted) {
            return;
        }
        log.info("Starting MySQL container | image={}", TestContainerImages.MYSQL_IMAGE);
        MYSQL.start();
        log.info("MySQL container started | jdbcUrl={} | mappedPort={}",
                MYSQL.getJdbcUrl(), MYSQL.getMappedPort(3306));
        verifyMysqlReady();
        log.info("MySQL health check passed");
        mysqlStarted = true;
    }

    static synchronized void startAll() {
        if (mysqlStarted && redpandaStarted) {
            return;
        }
        if (!mysqlStarted && !redpandaStarted) {
            log.info("Starting MySQL and Redpanda containers in parallel...");
            Startables.deepStart(Stream.of(MYSQL, REDPANDA)).join();
            log.info("MySQL container started | jdbcUrl={} | mappedPort={}",
                    MYSQL.getJdbcUrl(), MYSQL.getMappedPort(3306));
            log.info("Redpanda container started | bootstrapServers={} | schemaRegistryUrl={}",
                    REDPANDA.getBootstrapServers(), REDPANDA.getSchemaRegistryAddress());
            verifyMysqlReady();
            log.info("MySQL health check passed");
            mysqlStarted = true;
        } else if (!redpandaStarted) {
            log.info("Starting Redpanda container | image={}", TestContainerImages.REDPANDA_IMAGE);
            REDPANDA.start();
            log.info("Redpanda container started | bootstrapServers={} | schemaRegistryUrl={}",
                    REDPANDA.getBootstrapServers(), REDPANDA.getSchemaRegistryAddress());
        }
        System.setProperty("spring.kafka.bootstrap-servers", REDPANDA.getBootstrapServers());
        System.setProperty("spring.kafka.properties.schema.registry.url", REDPANDA.getSchemaRegistryAddress());
        log.debug("Set spring.kafka.bootstrap-servers={}", REDPANDA.getBootstrapServers());
        log.debug("Set spring.kafka.properties.schema.registry.url={}", REDPANDA.getSchemaRegistryAddress());
        redpandaStarted = true;
    }

    private static void verifyMysqlReady() {
        DriverManager.setLoginTimeout(5);
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             ResultSet rs = conn.createStatement().executeQuery("SELECT 1")) {
            if (!rs.next() || rs.getInt(1) != 1) {
                throw new IllegalStateException("MySQL health check failed");
            }
        } catch (Exception e) {
            log.error("MySQL health check failed — aborting test setup", e);
            throw new IllegalStateException("MySQL not ready after container start", e);
        }
    }

    private ContainerHolder() {
    }
}
