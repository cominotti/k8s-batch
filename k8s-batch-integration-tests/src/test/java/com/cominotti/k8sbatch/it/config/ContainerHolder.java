// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * Holds shared Testcontainer instances with decoupled lifecycle.
 * <p>
 * Standalone tests call {@link #startMysqlOnly()} to avoid starting Kafka.
 * Remote-partitioning tests call {@link #startAll()} to start both in parallel.
 */
final class ContainerHolder {

    private static final Logger log = LoggerFactory.getLogger(ContainerHolder.class);

    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0")
                    .withDatabaseName("k8sbatch")
                    .withUsername("test")
                    .withPassword("test");

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.0")
                    .withStartupTimeout(Duration.ofSeconds(120));

    private static volatile boolean mysqlStarted = false;
    private static volatile boolean kafkaStarted = false;

    static synchronized void startMysqlOnly() {
        if (mysqlStarted) {
            return;
        }
        log.info("Starting MySQL container (mysql:8.0)...");
        MYSQL.start();
        log.info("MySQL container started | jdbcUrl={} | mappedPort={}",
                MYSQL.getJdbcUrl(), MYSQL.getMappedPort(3306));
        verifyMysqlReady();
        log.info("MySQL health check passed");
        mysqlStarted = true;
    }

    static synchronized void startAll() {
        if (mysqlStarted && kafkaStarted) {
            return;
        }
        if (!mysqlStarted && !kafkaStarted) {
            log.info("Starting MySQL and Kafka containers in parallel...");
            Startables.deepStart(Stream.of(MYSQL, KAFKA)).join();
            log.info("MySQL container started | jdbcUrl={} | mappedPort={}",
                    MYSQL.getJdbcUrl(), MYSQL.getMappedPort(3306));
            log.info("Kafka container started | bootstrapServers={}", KAFKA.getBootstrapServers());
            verifyMysqlReady();
            log.info("MySQL health check passed");
            mysqlStarted = true;
        } else if (!kafkaStarted) {
            log.info("Starting Kafka container (cp-kafka:7.7.0)...");
            KAFKA.start();
            log.info("Kafka container started | bootstrapServers={}", KAFKA.getBootstrapServers());
        }
        System.setProperty("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
        log.debug("Set spring.kafka.bootstrap-servers={}", KAFKA.getBootstrapServers());
        kafkaStarted = true;
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
