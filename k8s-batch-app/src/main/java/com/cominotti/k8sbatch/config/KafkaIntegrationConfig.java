package com.cominotti.k8sbatch.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.integration.partition.BeanFactoryStepLocator;
import org.springframework.batch.integration.partition.StepExecutionRequestHandler;
import org.springframework.beans.factory.BeanFactory;
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
    public DirectChannel outboundReplies() {
        return new DirectChannel();
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

    private Map<String, Object> consumerConfig(String groupId) {
        return Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, groupId,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JacksonJsonDeserializer.class,
                JacksonJsonDeserializer.TRUSTED_PACKAGES, "*"
        );
    }

    @Bean
    public ConsumerFactory<String, Object> requestsConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(consumerConfig("k8s-batch-workers"));
    }


    // --- Outbound: Manager sends partition requests to Kafka ---

    @Bean
    public IntegrationFlow outboundRequestsFlow() {
        return IntegrationFlow.from(outboundRequests())
                .handle(Kafka.outboundChannelAdapter(partitionProducerFactory())
                        .topic(requestsTopic))
                .get();
    }

    // --- Worker: Receive partition requests from Kafka, process, send replies ---

    @Bean
    public StepExecutionRequestHandler stepExecutionRequestHandler(
            JobRepository jobRepository, BeanFactory beanFactory) {
        StepExecutionRequestHandler handler = new StepExecutionRequestHandler();
        handler.setJobRepository(jobRepository);
        BeanFactoryStepLocator stepLocator = new BeanFactoryStepLocator();
        stepLocator.setBeanFactory(beanFactory);
        handler.setStepLocator(stepLocator);
        return handler;
    }

    @Bean
    public IntegrationFlow workerFlow(StepExecutionRequestHandler stepExecutionRequestHandler) {
        return IntegrationFlow.from(
                        Kafka.messageDrivenChannelAdapter(
                                requestsConsumerFactory(),
                                requestsTopic))
                .handle(stepExecutionRequestHandler)
                .channel(outboundReplies())
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

}
