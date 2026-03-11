package com.cominotti.k8sbatch.it.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

@TestConfiguration(proxyBeanMethods = false)
public class SharedContainersConfig {

    static final MySQLContainer MYSQL =
            new MySQLContainer("mysql:8.0")
                    .withDatabaseName("k8sbatch")
                    .withUsername("test")
                    .withPassword("test");

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.0");

    static {
        MYSQL.start();
        KAFKA.start();
        System.setProperty("spring.kafka.bootstrap-servers", KAFKA.getBootstrapServers());
        verifyMysqlReady();
        createAndVerifyKafkaTopics();
    }

    private static void verifyMysqlReady() {
        try (Connection conn = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             ResultSet rs = conn.createStatement().executeQuery("SELECT 1")) {
            if (!rs.next() || rs.getInt(1) != 1) {
                throw new IllegalStateException("MySQL health check failed");
            }
        } catch (Exception e) {
            throw new IllegalStateException("MySQL not ready after container start", e);
        }
    }

    private static void createAndVerifyKafkaTopics() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("batch-partition-requests", 1, (short) 1),
                    new NewTopic("batch-partition-replies", 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);

            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            if (!topics.contains("batch-partition-requests") || !topics.contains("batch-partition-replies")) {
                throw new IllegalStateException(
                        "Kafka topics not created. Available: " + topics);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create/verify Kafka topics", e);
        }
    }

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return MYSQL;
    }

    @Bean
    ConfluentKafkaContainer kafkaContainer() {
        return KAFKA;
    }
}
