// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.integration.partition.StepExecutionRequestHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.NullChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.dsl.Transformers;
import org.springframework.integration.jms.dsl.Jms;

import jakarta.jms.ConnectionFactory;

/**
 * JMS transport for remote partitioning: outbound and inbound {@link IntegrationFlow} beans
 * that bridge the shared {@link DirectChannel} (from {@link RemotePartitioningBaseConfig}) to a
 * JMS queue. Works with any JMS-compatible broker — tested with RabbitMQ (via
 * {@code rabbitmq-jms} client) and AWS SQS (via {@code amazon-sqs-java-messaging-lib}).
 *
 * <p>The broker selection happens at the {@link ConnectionFactory} level: configure either
 * {@code com.rabbitmq.jms.admin.RMQConnectionFactory} or
 * {@code com.amazon.sqs.javamessaging.SQSConnectionFactory} as the JMS connection factory bean.
 * Spring Boot's {@code JmsAutoConfiguration} activates on any {@code ConnectionFactory} in the
 * context and provides the {@code JmsTemplate} used by the outbound adapter.
 *
 * <p>Worker pods consume from the same JMS queue, providing natural competing-consumer semantics.
 * If a worker crashes without acknowledging, the message is redelivered by the broker (requeued
 * by RabbitMQ, returned via visibility timeout by SQS).
 *
 * <p>Serialization uses Java object serialization via {@link Transformers#serializer()}, matching
 * the Kafka transport's approach. JMS {@code BytesMessage} handles the {@code byte[]} payload
 * natively — no manual Base64 encoding needed (the SQS JMS client handles this internally).
 *
 * <p>Activated under the {@code remote-jms} profile. Must be combined with
 * {@code remote-partitioning} (which provides the shared manager steps, channel, and handler).
 *
 * @see RemotePartitioningBaseConfig
 * @see RemotePartitioningKafkaConfig
 */
@Configuration
@Profile("remote-jms")
public class RemotePartitioningJmsConfig {

    private static final Logger log = LoggerFactory.getLogger(RemotePartitioningJmsConfig.class);

    private final String requestsQueue;

    /**
     * Injects JMS queue configuration.
     *
     * @param requestsQueue JMS queue name for partition requests
     */
    public RemotePartitioningJmsConfig(
            @Value("${batch.jms.requests-queue:batch-partition-requests}") String requestsQueue) {
        this.requestsQueue = requestsQueue;
        log.info("RemotePartitioningJmsConfig initialized | requestsQueue={}", requestsQueue);
    }

    // ── Outbound: Manager → JMS Queue ────────────────────────────

    /**
     * Spring Integration flow that serializes {@code StepExecutionRequest} objects to byte arrays
     * (Java serialization) and publishes them to the JMS partition requests queue.
     *
     * @param managerRequestsChannel shared channel from {@link RemotePartitioningBaseConfig}
     * @param connectionFactory      JMS connection factory (RabbitMQ or SQS, auto-configured)
     * @return integration flow: managerRequestsChannel -> Java serialization -> JMS producer
     */
    @Bean
    public IntegrationFlow managerOutboundRequestsFlow(
            DirectChannel managerRequestsChannel, ConnectionFactory connectionFactory) {
        log.info("Configuring JMS outbound flow | queue={}", requestsQueue);
        return IntegrationFlow.from(managerRequestsChannel)
                .transform(Transformers.serializer())
                .handle(Jms.outboundAdapter(connectionFactory)
                        .destination(requestsQueue))
                .get();
    }

    // ── Inbound: JMS Queue → Worker Handler ──────────────────────

    /**
     * Spring Integration flow that consumes serialized partition requests from the JMS queue,
     * deserializes them (with a whitelist restricted to Spring Batch and JDK classes), and
     * dispatches them to the {@link StepExecutionRequestHandler}. Results are discarded via
     * {@link NullChannel} because the manager detects completion by polling the
     * {@link org.springframework.batch.core.repository.JobRepository JobRepository}.
     *
     * <p>Uses a message-driven channel adapter (push-based) for efficient message retrieval.
     * The JMS provider handles acknowledgment: messages are acknowledged after the handler
     * completes successfully. If the handler throws, the message is rolled back and redelivered.
     *
     * @param connectionFactory              JMS connection factory (auto-configured)
     * @param stepExecutionRequestHandler    handler from {@link RemotePartitioningBaseConfig}
     * @return integration flow: JMS consumer -> deserialization -> step handler -> NullChannel
     */
    @Bean
    public IntegrationFlow workerFlow(
            ConnectionFactory connectionFactory,
            StepExecutionRequestHandler stepExecutionRequestHandler) {
        log.info("Configuring JMS inbound flow | queue={}", requestsQueue);
        return IntegrationFlow.from(Jms.messageDrivenChannelAdapter(connectionFactory)
                        .destination(requestsQueue))
                .transform(Transformers.deserializer(RemotePartitioningBaseConfig.DESERIALIZATION_ALLOWLIST))
                .handle(stepExecutionRequestHandler)
                .channel(new NullChannel())
                .get();
    }
}
