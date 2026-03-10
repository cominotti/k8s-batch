package com.cominotti.k8sbatch.it.config;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.kafka.ConfluentKafkaContainer;

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
    }

    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return MYSQL;
    }

    @Bean
    @ServiceConnection
    ConfluentKafkaContainer kafkaContainer() {
        return KAFKA;
    }
}
