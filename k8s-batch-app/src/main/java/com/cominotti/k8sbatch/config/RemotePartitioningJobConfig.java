package com.cominotti.k8sbatch.config;

import com.cominotti.k8sbatch.batch.common.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.filerange.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.MultiFilePartitioner;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.integration.partition.BeanFactoryStepLocator;
import org.springframework.batch.integration.partition.RemotePartitioningManagerStepBuilderFactory;
import org.springframework.batch.integration.partition.StepExecutionRequestHandler;
import org.springframework.beans.factory.BeanFactory;
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

@Configuration
@Profile("remote-partitioning")
public class RemotePartitioningJobConfig {

    private static final Logger log = LoggerFactory.getLogger(RemotePartitioningJobConfig.class);
    private static final long PARTITION_TIMEOUT_MS = 60_000;
    private static final String WORKER_CONSUMER_GROUP = "k8s-batch-workers";

    private final BatchPartitionProperties partitionProperties;
    private final LoggingStepExecutionListener stepExecutionListener;
    private final String bootstrapServers;
    private final String requestsTopic;

    public RemotePartitioningJobConfig(
            BatchPartitionProperties partitionProperties,
            LoggingStepExecutionListener stepExecutionListener,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${batch.kafka.requests-topic:batch-partition-requests}") String requestsTopic) {
        this.partitionProperties = partitionProperties;
        this.stepExecutionListener = stepExecutionListener;
        this.bootstrapServers = bootstrapServers;
        this.requestsTopic = requestsTopic;
        log.info("RemotePartitioningJobConfig initialized | bootstrapServers={} | requestsTopic={} | gridSize={} | chunkSize={}",
                bootstrapServers, requestsTopic, partitionProperties.gridSize(), partitionProperties.chunkSize());
    }

    // ── Builder Factory ──────────────────────────────────────────

    @Bean
    public RemotePartitioningManagerStepBuilderFactory managerStepBuilderFactory(
            JobRepository jobRepository) {
        return new RemotePartitioningManagerStepBuilderFactory(jobRepository);
    }

    // ── Channels ─────────────────────────────────────────────────

    @Bean
    public DirectChannel managerRequestsChannel() {
        return new DirectChannel();
    }

    // ── Kafka Factories (Java serialization) ─────────────────────

    @Bean
    public ProducerFactory<String, byte[]> partitionProducerFactory() {
        return new DefaultKafkaProducerFactory<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class
        ));
    }

    @Bean
    public ConsumerFactory<String, byte[]> requestsConsumerFactory() {
        return new DefaultKafkaConsumerFactory<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, WORKER_CONSUMER_GROUP,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class
        ));
    }

    // ── Manager: Outbound requests to Kafka ──────────────────────

    @Bean
    public IntegrationFlow managerOutboundRequestsFlow() {
        log.info("Configuring manager outbound flow | topic={}", requestsTopic);
        return IntegrationFlow.from(managerRequestsChannel())
                .transform(Transformers.serializer())
                .handle(Kafka.outboundChannelAdapter(partitionProducerFactory())
                        .topic(requestsTopic))
                .get();
    }

    // ── Worker: Inbound requests from Kafka → handler ────────────

    @Bean
    public StepExecutionRequestHandler stepExecutionRequestHandler(
            JobRepository jobRepository, BeanFactory beanFactory) {
        log.info("Configuring StepExecutionRequestHandler for worker-side processing");
        StepExecutionRequestHandler handler = new StepExecutionRequestHandler();
        handler.setJobRepository(jobRepository);
        BeanFactoryStepLocator stepLocator = new BeanFactoryStepLocator();
        stepLocator.setBeanFactory(beanFactory);
        handler.setStepLocator(stepLocator);
        return handler;
    }

    @Bean
    public IntegrationFlow workerFlow(StepExecutionRequestHandler stepExecutionRequestHandler) {
        log.info("Configuring worker inbound flow | topic={} | consumerGroup={}", requestsTopic, WORKER_CONSUMER_GROUP);
        return IntegrationFlow.from(
                        Kafka.messageDrivenChannelAdapter(
                                requestsConsumerFactory(), requestsTopic))
                .transform(Transformers.deserializer("org.springframework.batch.*", "java.util.*", "java.lang.*"))
                .handle(stepExecutionRequestHandler)
                .channel(new NullChannel())
                .get();
    }

    // ── Manager Steps (builder API, polling mode) ────────────────

    @Bean
    public Step fileRangeManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            FileRangePartitioner fileRangePartitioner) {
        log.info("Configuring remote manager step 'fileRangeManagerStep' | gridSize={} | timeout={}ms",
                partitionProperties.gridSize(), PARTITION_TIMEOUT_MS);
        return factory.get("fileRangeManagerStep")
                .partitioner("fileRangeWorkerStep", fileRangePartitioner)
                .gridSize(partitionProperties.gridSize())
                .outputChannel(managerRequestsChannel())
                .timeout(PARTITION_TIMEOUT_MS)
                .listener(stepExecutionListener)
                .build();
    }

    @Bean
    public Step multiFileManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            MultiFilePartitioner multiFilePartitioner) {
        log.info("Configuring remote manager step 'multiFileManagerStep' | gridSize={} | timeout={}ms",
                partitionProperties.gridSize(), PARTITION_TIMEOUT_MS);
        return factory.get("multiFileManagerStep")
                .partitioner("multiFileWorkerStep", multiFilePartitioner)
                .gridSize(partitionProperties.gridSize())
                .outputChannel(managerRequestsChannel())
                .timeout(PARTITION_TIMEOUT_MS)
                .listener(stepExecutionListener)
                .build();
    }
}
