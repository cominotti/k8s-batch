// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.redpanda.RedpandaContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Test configuration for remote-partitioning tests. Starts MySQL + Redpanda (Kafka-compatible
 * broker) and creates the Kafka topics needed for partition request messaging.
 *
 * <p>Container startup and topic creation happen in the static initializer (before the Spring
 * context loads) to ensure Kafka is ready when Spring Batch integration beans are created.
 */
@TestConfiguration(proxyBeanMethods = false)
public class SharedContainersConfig {

    private static final Logger log = LoggerFactory.getLogger(SharedContainersConfig.class);

    static {
        ContainerHolder.startAll();

        log.info("Creating and verifying Kafka topics...");
        createAndVerifyKafkaTopics();
    }

    private static void createAndVerifyKafkaTopics() {
        try (AdminClient admin = AdminClient.create(
                Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, ContainerHolder.REDPANDA.getBootstrapServers()))) {
            // Topic names must match the production application config
            admin.createTopics(List.of(
                    new NewTopic("batch-partition-requests", 1, (short) 1),
                    new NewTopic("batch-partition-replies", 1, (short) 1)
            )).all().get(30, TimeUnit.SECONDS);

            Set<String> topics = admin.listTopics().names().get(10, TimeUnit.SECONDS);
            if (!topics.contains("batch-partition-requests") || !topics.contains("batch-partition-replies")) {
                throw new IllegalStateException(
                        "Kafka topics not created. Available: " + topics);
            }
            log.info("Kafka topics verified: {}", topics);
        } catch (IllegalStateException e) {
            log.error("Kafka topic creation/verification failed — aborting test setup", e);
            throw e;
        } catch (Exception e) {
            log.error("Kafka topic creation/verification failed — aborting test setup", e);
            throw new IllegalStateException("Failed to create/verify Kafka topics", e);
        }
    }

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return ContainerHolder.MYSQL;
    }

    // No @ServiceConnection — Kafka properties are set via System.setProperty in ContainerHolder
    // because spring-boot-kafka auto-config conflicts with manual KafkaIntegrationConfig
    @Bean
    RedpandaContainer redpandaContainer() {
        return ContainerHolder.REDPANDA;
    }
}
