// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.config;

import com.cominotti.k8sbatch.batch.common.domain.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.adapters.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.filerange.domain.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.domain.MultiFilePartitioner;
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

/**
 * Configures Kafka-based remote partitioning for horizontally-scaled batch processing.
 *
 * <p>Architecture: the manager step serializes {@code StepExecutionRequest} objects via Java
 * serialization, publishes them to the {@code batch-partition-requests} Kafka topic. Worker pods
 * consume these requests, resolve the corresponding {@link org.springframework.batch.core.step.Step}
 * bean by name via {@link BeanFactoryStepLocator}, and execute it locally. The manager detects
 * worker completion by polling the {@link JobRepository} — no reply channel is used, which avoids
 * fragile {@code StepExecution} serialization through Kafka.
 *
 * <p>Activated only under the {@code remote-partitioning} profile (the default). When the
 * {@code standalone} profile is active, {@link com.cominotti.k8sbatch.config.StandaloneJobConfig
 * StandaloneJobConfig} provides the manager steps instead — the two configs are mutually exclusive.
 */
@Configuration
@Profile("remote-partitioning")
public class RemotePartitioningJobConfig {

    private static final Logger log = LoggerFactory.getLogger(RemotePartitioningJobConfig.class);

    // All worker pods share one consumer group so Kafka distributes partition requests among them
    private static final String WORKER_CONSUMER_GROUP = "k8s-batch-workers";

    private final BatchPartitionProperties partitionProperties;
    private final LoggingStepExecutionListener stepExecutionListener;
    private final String bootstrapServers;
    private final String requestsTopic;

    /**
     * Injects Kafka connection properties and shared batch infrastructure.
     *
     * @param partitionProperties   grid size, chunk size, and polling timeout for manager steps
     * @param stepExecutionListener logs step start/end events on manager steps
     * @param bootstrapServers      Kafka broker addresses
     * @param requestsTopic         Kafka topic for publishing partition requests to workers
     */
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

    // ── Kafka Factories (Java serialization, not JSON) ────────────
    // StepExecutionRequest is serialized as a byte[] via Java object serialization,
    // which is why ByteArraySerializer/Deserializer is used instead of JSON serializers.

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
                // "earliest" so restarted workers pick up any unprocessed partition requests
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class
        ));
    }

    // ── Manager: Outbound requests to Kafka ──────────────────────
    // Serializes StepExecutionRequest → byte[] and publishes to Kafka

    @Bean
    public IntegrationFlow managerOutboundRequestsFlow() {
        log.info("Configuring manager outbound flow | topic={}", requestsTopic);
        return IntegrationFlow.from(managerRequestsChannel())
                .transform(Transformers.serializer()) // Java serialization → byte[]
                .handle(Kafka.outboundChannelAdapter(partitionProducerFactory())
                        .topic(requestsTopic))
                .get();
    }

    // ── Worker: Inbound requests from Kafka → handler ────────────
    // Deserializes byte[] → StepExecutionRequest, looks up the Step by name, executes it

    @Bean
    public StepExecutionRequestHandler stepExecutionRequestHandler(
            JobRepository jobRepository, BeanFactory beanFactory) {
        log.info("Configuring StepExecutionRequestHandler for worker-side processing");
        StepExecutionRequestHandler handler = new StepExecutionRequestHandler();
        handler.setJobRepository(jobRepository);
        // BeanFactoryStepLocator resolves Step beans by name (e.g., "fileRangeWorkerStep").
        // The step name in the request must match a bean name defined via BatchStepNames constants.
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
                // Deserialization whitelist: only allow Spring Batch and JDK classes
                .transform(Transformers.deserializer("org.springframework.batch.*", "java.util.*", "java.lang.*"))
                .handle(stepExecutionRequestHandler)
                // NullChannel discards the handler result — the manager detects completion by
                // polling JobRepository, not by receiving a reply message through Kafka
                .channel(new NullChannel())
                .get();
    }

    // ── Manager Steps (builder API, polling mode) ────────────────
    // The manager polls JobRepository until all workers complete or timeout expires

    @Bean
    public Step fileRangeManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            FileRangePartitioner fileRangePartitioner) {
        return buildRemoteManagerStep(factory, BatchStepNames.FILE_RANGE_MANAGER_STEP,
                BatchStepNames.FILE_RANGE_WORKER_STEP, fileRangePartitioner);
    }

    @Bean
    public Step multiFileManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            MultiFilePartitioner multiFilePartitioner) {
        return buildRemoteManagerStep(factory, BatchStepNames.MULTI_FILE_MANAGER_STEP,
                BatchStepNames.MULTI_FILE_WORKER_STEP, multiFilePartitioner);
    }

    private Step buildRemoteManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            String managerStepName, String workerStepName,
            org.springframework.batch.core.partition.Partitioner partitioner) {
        log.info("Configuring remote manager step '{}' | gridSize={} | timeout={}ms",
                managerStepName, partitionProperties.gridSize(), partitionProperties.timeoutMs());
        return factory.get(managerStepName)
                .partitioner(workerStepName, partitioner)
                .gridSize(partitionProperties.gridSize())
                .outputChannel(managerRequestsChannel())
                .timeout(partitionProperties.timeoutMs())
                .listener(stepExecutionListener)
                .build();
    }
}
