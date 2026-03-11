package com.cominotti.k8sbatch.it.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.util.List;
import java.util.Map;

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
        createKafkaTopics();
    }

    private static void createKafkaTopics() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            admin.createTopics(List.of(
                    new NewTopic("batch-partition-requests", 1, (short) 1),
                    new NewTopic("batch-partition-replies", 1, (short) 1)
            )).all().get();
        } catch (Exception e) {
            throw new RuntimeException("Failed to create Kafka topics", e);
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
