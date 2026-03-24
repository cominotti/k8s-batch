// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.oracle.OracleContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.stream.Stream;

/**
 * Holds shared Testcontainer instances with decoupled lifecycle.
 * <p>
 * Standalone tests call {@link #startMysqlOnly()} to avoid starting Redpanda.
 * Kafka remote-partitioning tests call {@link #startAll()} to start MySQL + Redpanda in parallel.
 * JMS remote-partitioning tests call {@link #startMysqlAndRabbitMq()} to start MySQL + RabbitMQ.
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

    // Lazily initialized — RabbitMQ is only needed by JMS integration tests, so we avoid Docker
    // daemon contact and Ryuk registration overhead for Kafka and standalone test runs.
    private static RabbitMQContainer rabbitmq;

    // Lazily initialized — Oracle is only needed by OracleSchemaIT, so we avoid Docker daemon
    // contact and Ryuk registration overhead for the majority of tests that don't use Oracle.
    private static OracleContainer oracle;

    private static volatile boolean mysqlStarted = false;
    private static volatile boolean redpandaStarted = false;
    private static volatile boolean rabbitmqStarted = false;
    private static volatile boolean oracleStarted = false;

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
        // Two branches: (1) both containers need starting, (2) MySQL already started by a
        // standalone test suite and only Redpanda needs to be added
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
        // Kafka properties are set via System.setProperty instead of @ServiceConnection because
        // spring-boot-kafka auto-config conflicts with the manual KafkaIntegrationConfig used
        // for remote partitioning channels. @ServiceConnection requires spring-boot-kafka, which
        // would create duplicate consumer/producer factories.
        String bootstrapServers = REDPANDA.getBootstrapServers();
        String schemaRegistryUrl = REDPANDA.getSchemaRegistryAddress();
        System.setProperty("spring.kafka.bootstrap-servers", bootstrapServers);
        System.setProperty("spring.kafka.properties.schema.registry.url", schemaRegistryUrl);
        log.debug("Set spring.kafka.bootstrap-servers={}", bootstrapServers);
        log.debug("Set spring.kafka.properties.schema.registry.url={}", schemaRegistryUrl);
        redpandaStarted = true;
    }

    static synchronized void startMysqlAndRabbitMq() {
        if (mysqlStarted && rabbitmqStarted) {
            return;
        }
        rabbitmq = new RabbitMQContainer(TestContainerImages.RABBITMQ_IMAGE);
        if (!mysqlStarted && !rabbitmqStarted) {
            log.info("Starting MySQL and RabbitMQ containers in parallel | mysqlImage={} | rabbitmqImage={}",
                    TestContainerImages.MYSQL_IMAGE, TestContainerImages.RABBITMQ_IMAGE);
            Startables.deepStart(Stream.of(MYSQL, rabbitmq)).join();
            log.info("MySQL container started | jdbcUrl={} | mappedPort={}",
                    MYSQL.getJdbcUrl(), MYSQL.getMappedPort(3306));
            log.info("RabbitMQ container started | host={} | amqpPort={}",
                    rabbitmq.getHost(), rabbitmq.getAmqpPort());
            verifyMysqlReady();
            log.info("MySQL health check passed");
            mysqlStarted = true;
        } else if (!rabbitmqStarted) {
            log.info("Starting RabbitMQ container | image={}", TestContainerImages.RABBITMQ_IMAGE);
            rabbitmq.start();
            log.info("RabbitMQ container started | host={} | amqpPort={}",
                    rabbitmq.getHost(), rabbitmq.getAmqpPort());
        }
        // No System.setProperty for spring.rabbitmq.* — JmsContainersConfig creates the
        // ConnectionFactory bean directly using getRabbitMq() accessors.
        rabbitmqStarted = true;
    }

    static RabbitMQContainer getRabbitMq() {
        return rabbitmq;
    }

    static synchronized void startOracleOnly() {
        if (oracleStarted) {
            return;
        }
        oracle = new OracleContainer(TestContainerImages.ORACLE_FREE_IMAGE)
                .withStartupTimeout(Duration.ofSeconds(180));
        log.info("Starting Oracle container | image={}", TestContainerImages.ORACLE_FREE_IMAGE);
        oracle.start();
        log.info("Oracle container started | jdbcUrl={} | mappedPort={}",
                oracle.getJdbcUrl(), oracle.getMappedPort(1521));
        verifyDatabaseReady(oracle.getJdbcUrl(), oracle.getUsername(), oracle.getPassword(),
                "SELECT 1 FROM DUAL", 10, "Oracle");
        log.info("Oracle health check passed");
        oracleStarted = true;
    }

    static OracleContainer getOracle() {
        return oracle;
    }

    private static void verifyDatabaseReady(String jdbcUrl, String username, String password,
                                            String healthQuery, int loginTimeoutSeconds,
                                            String databaseName) {
        DriverManager.setLoginTimeout(loginTimeoutSeconds);
        try (Connection conn = DriverManager.getConnection(jdbcUrl, username, password);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(healthQuery)) {
            if (!rs.next() || rs.getInt(1) != 1) {
                throw new IllegalStateException(databaseName + " health check failed");
            }
        } catch (Exception e) {
            log.error("{} health check failed — aborting test setup", databaseName, e);
            throw new IllegalStateException(databaseName + " not ready after container start", e);
        }
    }

    private static void verifyMysqlReady() {
        verifyDatabaseReady(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(),
                "SELECT 1", 5, "MySQL");
    }

    private ContainerHolder() {
    }
}
