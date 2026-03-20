// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.config;

import com.cominotti.k8sbatch.batch.common.domain.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
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

    /**
     * Creates the factory for building remote partitioning manager steps. Used by both
     * {@link #fileRangeManagerStep} and {@link #multiFileManagerStep}.
     *
     * @param jobRepository repository for persisting step execution metadata
     * @return factory for building manager steps with remote partitioning support
     */
    @Bean
    public RemotePartitioningManagerStepBuilderFactory managerStepBuilderFactory(
            JobRepository jobRepository) {
        return new RemotePartitioningManagerStepBuilderFactory(jobRepository);
    }

    // ── Channels ─────────────────────────────────────────────────

    /**
     * Spring Integration channel that receives {@code StepExecutionRequest} messages from the
     * manager step and routes them to the Kafka outbound flow.
     *
     * @return direct channel (synchronous — processes messages on the caller's thread)
     */
    @Bean
    public DirectChannel managerRequestsChannel() {
        return new DirectChannel();
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

    // ── Manager: Outbound requests to Kafka ──────────────────────

    /**
     * Spring Integration flow that serializes {@code StepExecutionRequest} objects to byte arrays
     * (Java serialization) and publishes them to the Kafka requests topic.
     *
     * @return integration flow: managerRequestsChannel -> Java serialization -> Kafka producer
     */
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

    /**
     * Worker-side handler that receives deserialized {@code StepExecutionRequest} messages,
     * resolves the target {@link org.springframework.batch.core.step.Step Step} bean by name via
     * {@link BeanFactoryStepLocator}, and executes it. Step names must match bean names defined
     * in {@link com.cominotti.k8sbatch.batch.common.domain.BatchStepNames BatchStepNames}.
     *
     * @param jobRepository repository for persisting step execution status
     * @param beanFactory   Spring bean factory for resolving step beans by name
     * @return configured handler for worker-side step execution
     */
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

    /**
     * Spring Integration flow that consumes serialized partition requests from Kafka, deserializes
     * them (with a whitelist restricted to Spring Batch and JDK classes), and dispatches them to
     * the {@link StepExecutionRequestHandler}. Results are discarded via {@link NullChannel}
     * because the manager detects completion by polling {@link JobRepository}.
     *
     * @param stepExecutionRequestHandler handler that resolves and executes the target step
     * @return integration flow: Kafka consumer -> deserialization -> step handler -> NullChannel
     */
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

    /**
     * Remote partitioning manager step for the file-range ETL job. Publishes partition requests
     * to Kafka and polls {@link JobRepository} until all workers complete or timeout expires.
     *
     * @param factory              builder factory for remote manager steps
     * @param fileRangePartitioner splits the CSV file into line-range partitions
     * @return configured manager step with Kafka outbound channel and polling timeout
     */
    @Bean
    public Step fileRangeManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            FileRangePartitioner fileRangePartitioner) {
        return buildRemoteManagerStep(factory, BatchStepNames.FILE_RANGE_MANAGER_STEP,
                BatchStepNames.FILE_RANGE_WORKER_STEP, fileRangePartitioner);
    }

    /**
     * Remote partitioning manager step for the multi-file ETL job. Publishes partition requests
     * to Kafka and polls {@link JobRepository} until all workers complete or timeout expires.
     *
     * @param factory              builder factory for remote manager steps
     * @param multiFilePartitioner assigns one CSV file per partition
     * @return configured manager step with Kafka outbound channel and polling timeout
     */
    @Bean
    public Step multiFileManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            MultiFilePartitioner multiFilePartitioner) {
        return buildRemoteManagerStep(factory, BatchStepNames.MULTI_FILE_MANAGER_STEP,
                BatchStepNames.MULTI_FILE_WORKER_STEP, multiFilePartitioner);
    }

    /**
     * Shared builder for remote manager steps. Configures partitioning, grid size, Kafka output
     * channel, polling timeout, and the step execution listener. No reply channel is used —
     * the manager detects worker completion by polling {@link JobRepository}, which avoids
     * fragile {@code StepExecution} serialization through Kafka.
     */
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
