// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import com.rabbitmq.jms.admin.RMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.mysql.MySQLContainer;

import jakarta.jms.ConnectionFactory;

/**
 * Test configuration for JMS remote-partitioning tests. Starts MySQL + RabbitMQ in parallel
 * and provides a JMS {@link ConnectionFactory} backed by RabbitMQ's JMS client.
 *
 * <p>The JMS {@code ConnectionFactory} bean is what Spring Boot's {@code JmsAutoConfiguration}
 * auto-detects to create the {@code JmsTemplate} injected by
 * {@link com.cominotti.k8sbatch.config.RemotePartitioningJmsConfig RemotePartitioningJmsConfig}.
 *
 * <p>This config validates that the JMS transport works end-to-end with a real RabbitMQ broker.
 * The same {@code RemotePartitioningJmsConfig} would work with SQS by swapping the
 * {@code ConnectionFactory} to {@code SQSConnectionFactory} — no code changes needed.
 */
@TestConfiguration(proxyBeanMethods = false)
public class JmsContainersConfig {

    private static final Logger log = LoggerFactory.getLogger(JmsContainersConfig.class);

    static {
        ContainerHolder.startMysqlAndRabbitMq();
    }

    /**
     * Exposes the MySQL container for {@code @ServiceConnection} auto-wiring.
     *
     * @return the shared MySQL container
     */
    @Bean
    @ServiceConnection
    MySQLContainer mysqlContainer() {
        return ContainerHolder.MYSQL;
    }

    /**
     * Creates a JMS {@link ConnectionFactory} backed by RabbitMQ's JMS client library.
     * Connects to the Testcontainer RabbitMQ instance using its dynamic host and port.
     *
     * @return JMS connection factory for RabbitMQ
     */
    @Bean
    ConnectionFactory jmsConnectionFactory() {
        RMQConnectionFactory factory = new RMQConnectionFactory();
        factory.setHost(ContainerHolder.getRabbitMq().getHost());
        factory.setPort(ContainerHolder.getRabbitMq().getAmqpPort());
        factory.setUsername(ContainerHolder.getRabbitMq().getAdminUsername());
        factory.setPassword(ContainerHolder.getRabbitMq().getAdminPassword());
        log.info("Created RMQConnectionFactory | host={} | port={}",
                ContainerHolder.getRabbitMq().getHost(), ContainerHolder.getRabbitMq().getAmqpPort());
        return factory;
    }
}
