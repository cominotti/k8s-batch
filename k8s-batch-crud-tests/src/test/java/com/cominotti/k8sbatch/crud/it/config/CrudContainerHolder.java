// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.crud.it.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Singleton holder for the MySQL Testcontainer used by CRUD integration tests.
 *
 * <p>Simplified version of the batch module's {@code ContainerHolder} — only MySQL, no
 * Kafka/Redpanda/RabbitMQ. Container is started once and reused across all tests.
 */
final class CrudContainerHolder {

    private static final Logger log = LoggerFactory.getLogger(CrudContainerHolder.class);

    static final MySQLContainer MYSQL = new MySQLContainer(CrudTestContainerImages.MYSQL_IMAGE)
            .withDatabaseName("k8scrud")
            .withUsername("test")
            .withPassword("test");

    private static volatile boolean started = false;

    /**
     * Starts the MySQL container if not already running. Thread-safe via synchronized.
     */
    static synchronized void startMysql() {
        if (started) {
            return;
        }
        log.info("Starting MySQL container | image={}", CrudTestContainerImages.MYSQL_IMAGE);
        MYSQL.start();
        log.info("MySQL container started | jdbcUrl={} | mappedPort={}",
                MYSQL.getJdbcUrl(), MYSQL.getMappedPort(3306));
        verifyMysqlReady();
        started = true;
    }

    private static void verifyMysqlReady() {
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT 1")) {
            if (!rs.next()) {
                throw new IllegalStateException("MySQL health check failed: no result from SELECT 1");
            }
            log.info("MySQL health check passed");
        } catch (Exception e) {
            throw new IllegalStateException("MySQL health check failed", e);
        }
    }

    private CrudContainerHolder() {
    }
}
