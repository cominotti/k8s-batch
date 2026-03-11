package com.cominotti.k8sbatch.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import com.cominotti.k8sbatch.batch.common.BatchPartitionProperties;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.dsl.IntegrationFlow;
import org.springframework.integration.kafka.dsl.Kafka;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import java.util.Map;

@Configuration
@Profile("remote-partitioning")
public class KafkaIntegrationConfig {

    private static final int QUEUE_CAPACITY_MULTIPLIER = 2;

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${batch.kafka.requests-topic:batch-partition-requests}")
    private String requestsTopic;

    @Value("${batch.kafka.replies-topic:batch-partition-replies}")
    private String repliesTopic;

    private final BatchPartitionProperties partitionProperties;

    public KafkaIntegrationConfig(BatchPartitionProperties partitionProperties) {
        this.partitionProperties = partitionProperties;
    }

    // --- Channels ---

    @Bean
    public DirectChannel outboundRequests() {
        return new DirectChannel();
    }

    @Bean
    public QueueChannel inboundRequests() {
        return new QueueChannel(partitionProperties.gridSize() * QUEUE_CAPACITY_MULTIPLIER);
    }

    @Bean
    public DirectChannel outboundReplies() {
        return new DirectChannel();
    }

    @Bean
    public QueueChannel inboundReplies() {
        return new QueueChannel(partitionProperties.gridSize() * QUEUE_CAPACITY_MULTIPLIER);
    }

    // --- Producer/Consumer Factories ---

    @Bean
    public ProducerFactory<String, Object> partitionProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class
        ));
    }

    @Bean
    public ConsumerFactory<String, Object> partitionConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "k8s-batch-workers",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class,
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "org.springframework.batch.core.step,org.springframework.batch.infrastructure.item,java.util"
        ));
    }

    // --- Outbound: Manager sends partition requests to Kafka ---

    @Bean
    public IntegrationFlow outboundRequestsFlow() {
        return IntegrationFlow.from(outboundRequests())
                .handle(Kafka.outboundChannelAdapter(partitionProducerFactory())
                        .topic(requestsTopic))
                .get();
    }

    // --- Inbound: Workers receive partition requests from Kafka ---

    @Bean
    public IntegrationFlow inboundRequestsFlow() {
        return IntegrationFlow.from(
                        Kafka.messageDrivenChannelAdapter(
                                partitionConsumerFactory(),
                                requestsTopic))
                .channel(inboundRequests())
                .get();
    }

    // --- Outbound: Workers send replies to Kafka ---

    @Bean
    public IntegrationFlow outboundRepliesFlow() {
        return IntegrationFlow.from(outboundReplies())
                .handle(Kafka.outboundChannelAdapter(partitionProducerFactory())
                        .topic(repliesTopic))
                .get();
    }

    // --- Inbound: Manager receives replies from Kafka ---

    @Bean
    public IntegrationFlow inboundRepliesFlow() {
        return IntegrationFlow.from(
                        Kafka.messageDrivenChannelAdapter(
                                partitionConsumerFactory(),
                                repliesTopic))
                .channel(inboundReplies())
                .get();
    }
}
