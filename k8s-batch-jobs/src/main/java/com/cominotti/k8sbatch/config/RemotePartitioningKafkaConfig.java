// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
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
import org.springframework.integration.kafka.dsl.Kafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;

import java.util.Map;

/**
 * Kafka transport for remote partitioning: outbound and inbound {@link IntegrationFlow} beans
 * that bridge the shared {@link DirectChannel} (from {@link RemotePartitioningBaseConfig}) to
 * Kafka topics.
 *
 * <p>Architecture: {@code StepExecutionRequest} objects are serialized via Java object serialization,
 * published to the {@code batch-partition-requests} Kafka topic. Worker pods consume these requests
 * via a shared consumer group, deserialize them (with a security whitelist), and dispatch to the
 * {@link StepExecutionRequestHandler} from the base config.
 *
 * <p>Activated under the {@code remote-kafka} profile. Must be combined with
 * {@code remote-partitioning} (which provides the shared manager steps, channel, and handler).
 *
 * @see RemotePartitioningBaseConfig
 */
@Configuration
@Profile("remote-kafka")
public class RemotePartitioningKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(RemotePartitioningKafkaConfig.class);

    // All worker pods share one consumer group so Kafka distributes partition requests among them
    private static final String WORKER_CONSUMER_GROUP = "k8s-batch-workers";

    private final String bootstrapServers;
    private final String requestsTopic;

    /**
     * Injects Kafka connection properties.
     *
     * @param bootstrapServers Kafka broker addresses
     * @param requestsTopic    Kafka topic for publishing partition requests to workers
     */
    public RemotePartitioningKafkaConfig(
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${batch.kafka.requests-topic:batch-partition-requests}") String requestsTopic) {
        this.bootstrapServers = bootstrapServers;
        this.requestsTopic = requestsTopic;
        log.info("RemotePartitioningKafkaConfig initialized | bootstrapServers={} | requestsTopic={}",
                bootstrapServers, requestsTopic);
    }

    // ── Kafka Factories ────────────────────────────────────────────

    /**
     * Kafka producer factory for publishing serialized partition requests. Uses
     * {@code ByteArraySerializer} because {@code StepExecutionRequest} objects are serialized
     * via Java object serialization by the outbound flow, not as JSON or Avro.
     *
     * @return producer factory for the partition requests topic
     */
    @Bean
    public ProducerFactory<String, byte[]> partitionProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class
        ));
    }

    /**
     * Kafka consumer factory for receiving serialized partition requests on worker pods. All
     * workers share a single consumer group so Kafka load-balances requests across them.
     * {@code earliest} offset reset ensures restarted workers pick up unprocessed requests.
     *
     * @return consumer factory with the shared worker consumer group
     */
    @Bean
    public ConsumerFactory<String, byte[]> requestsConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, WORKER_CONSUMER_GROUP,
                // "earliest" so restarted workers pick up any unprocessed partition requests
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class
        ));
    }

    // ── Outbound: Manager → Kafka ────────────────────────────────

    /**
     * Spring Integration flow that serializes {@code StepExecutionRequest} objects to byte arrays
     * (Java serialization) and publishes them to the Kafka requests topic.
     *
     * @param managerRequestsChannel shared channel from {@link RemotePartitioningBaseConfig}
     * @return integration flow: managerRequestsChannel -> Java serialization -> Kafka producer
     */
    @Bean
    public IntegrationFlow managerOutboundRequestsFlow(DirectChannel managerRequestsChannel) {
        log.info("Configuring Kafka outbound flow | topic={}", requestsTopic);
        return IntegrationFlow.from(managerRequestsChannel)
                .transform(Transformers.serializer()) // Java serialization → byte[]
                .handle(Kafka.outboundChannelAdapter(partitionProducerFactory())
                        .topic(requestsTopic))
                .get();
    }

    // ── Inbound: Kafka → Worker Handler ──────────────────────────

    /**
     * Spring Integration flow that consumes serialized partition requests from Kafka, deserializes
     * them (with a whitelist restricted to Spring Batch and JDK classes), and dispatches them to
     * the {@link StepExecutionRequestHandler}. Results are discarded via {@link NullChannel}
     * because the manager detects completion by polling the
     * {@link org.springframework.batch.core.repository.JobRepository JobRepository}.
     *
     * @param stepExecutionRequestHandler handler from {@link RemotePartitioningBaseConfig}
     * @return integration flow: Kafka consumer -> deserialization -> step handler -> NullChannel
     */
    @Bean
    public IntegrationFlow workerFlow(StepExecutionRequestHandler stepExecutionRequestHandler) {
        log.info("Configuring Kafka inbound flow | topic={} | consumerGroup={}", requestsTopic, WORKER_CONSUMER_GROUP);
        return IntegrationFlow.from(
                        Kafka.messageDrivenChannelAdapter(
                                requestsConsumerFactory(), requestsTopic))
                .transform(Transformers.deserializer(RemotePartitioningBaseConfig.DESERIALIZATION_ALLOWLIST))
                .handle(stepExecutionRequestHandler)
                // NullChannel discards the handler result — the manager detects completion by
                // polling JobRepository, not by receiving a reply message through Kafka
                .channel(new NullChannel())
                .get();
    }
}
