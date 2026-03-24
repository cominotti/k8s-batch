// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction.config;

import com.cominotti.k8sbatch.avro.EnrichedTransactionEvent;
import com.cominotti.k8sbatch.avro.TransactionEvent;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingJobExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.transaction.adapters.persistingtransactions.jdbc.EnrichedTransactionWriter;
import com.cominotti.k8sbatch.batch.transaction.domain.TransactionEnrichmentProcessor;
import com.cominotti.k8sbatch.batch.transaction.domain.TransactionJobProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.kafka.KafkaItemReader;
import org.springframework.batch.infrastructure.item.kafka.KafkaItemWriter;
import org.springframework.batch.infrastructure.item.kafka.builder.KafkaItemReaderBuilder;
import org.springframework.batch.infrastructure.item.support.CompositeItemWriter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import org.apache.kafka.common.TopicPartition;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Defines the {@code transactionEnrichmentJob}: reads Avro {@link TransactionEvent} messages from
 * Kafka, enriches them (exchange rate, USD conversion, risk score), and writes enriched events to
 * both MySQL and a Kafka output topic via {@link CompositeItemWriter}.
 *
 * <p>This is a <strong>single chunk step</strong> (not partitioned). Unlike the CSV ETL jobs which
 * use a manager + worker partition pattern, this job reads directly from Kafka via
 * {@link KafkaItemReader}. Parallelism comes from Kafka's partition assignment across pod replicas,
 * not from Spring Batch partitioning.
 *
 * <p>Activated only under the {@code remote-partitioning} profile because the job requires Kafka.
 */
@Configuration
@Profile("remote-kafka")
public class TransactionEnrichmentJobConfig {

    private static final Logger log = LoggerFactory.getLogger(TransactionEnrichmentJobConfig.class);

    private final TransactionJobProperties jobProperties;
    private final LoggingJobExecutionListener jobExecutionListener;
    private final LoggingStepExecutionListener stepExecutionListener;

    /**
     * Injects job-specific properties and shared batch listeners.
     *
     * @param jobProperties        topic names, chunk size, and poll timeout for this job
     * @param jobExecutionListener logs job start/end events
     * @param stepExecutionListener logs step start/end events
     */
    public TransactionEnrichmentJobConfig(
            TransactionJobProperties jobProperties,
            LoggingJobExecutionListener jobExecutionListener,
            LoggingStepExecutionListener stepExecutionListener) {
        this.jobProperties = jobProperties;
        this.jobExecutionListener = jobExecutionListener;
        this.stepExecutionListener = stepExecutionListener;
        log.info("TransactionEnrichmentJobConfig initialized | inputTopic={} | outputTopic={} | chunkSize={}",
                jobProperties.inputTopic(), jobProperties.outputTopic(), jobProperties.chunkSize());
    }

    // ── Reader ───────────────────────────────────────────────────────

    /**
     * Creates a partition-scoped Kafka reader for consuming {@link TransactionEvent} messages.
     *
     * <p>{@link KafkaItemReader} uses manual partition assignment ({@code assign()}) rather than
     * the consumer group protocol ({@code subscribe()}), so it requires explicit
     * partition-to-offset mappings. Kafka's automatic rebalancing is bypassed — the job parameter
     * {@code kafka.partitions} controls which partitions this instance reads. Offset 0 means
     * "start from the beginning" (overridden by saved state if {@code saveState=true} and a
     * previous execution checkpoint exists).
     *
     * @param consumerFactory Avro-deserializing consumer factory from
     *     {@link com.cominotti.k8sbatch.batch.transaction.adapters.streamingevents.kafka.TransactionKafkaConfig
     *     TransactionKafkaConfig}
     * @param partitions      comma-separated partition indices from job parameters (defaults to "0")
     * @return reader configured for the specified Kafka partitions
     */
    @Bean
    @StepScope
    public KafkaItemReader<String, TransactionEvent> transactionEventReader(
            @Qualifier("transactionConsumerFactory")
            ConsumerFactory<String, TransactionEvent> consumerFactory,
            @Value("#{jobParameters['kafka.partitions'] ?: '0'}") String partitions) {
        List<Integer> partitionList = parsePartitions(partitions);
        Map<TopicPartition, Long> partitionOffsets = new HashMap<>();
        partitionList.forEach(p -> partitionOffsets.put(
                new TopicPartition(jobProperties.inputTopic(), p), 0L));
        log.info("Configuring KafkaItemReader | topic={} | partitions={} | pollTimeoutSeconds={}",
                jobProperties.inputTopic(), partitionList, jobProperties.pollTimeoutSeconds());

        Properties consumerProps = new Properties();
        consumerFactory.getConfigurationProperties().forEach(
                (key, value) -> consumerProps.put(key, value));

        return new KafkaItemReaderBuilder<String, TransactionEvent>()
                .name("transactionEventReader")
                .topic(jobProperties.inputTopic())
                .consumerProperties(consumerProps)
                .partitions(partitionList)
                .partitionOffsets(partitionOffsets)
                .pollTimeout(Duration.ofSeconds(jobProperties.pollTimeoutSeconds()))
                .saveState(true)
                .build();
    }

    // ── Processor ────────────────────────────────────────────────────

    @Bean
    @StepScope
    public TransactionEnrichmentProcessor transactionEnrichmentProcessor() {
        return new TransactionEnrichmentProcessor();
    }

    // ── Writers ──────────────────────────────────────────────────────

    @Bean
    @StepScope
    public JdbcBatchItemWriter<EnrichedTransactionEvent> enrichedTransactionDbWriter(
            DataSource dataSource) {
        return EnrichedTransactionWriter.create(dataSource);
    }

    /**
     * Creates a partition-scoped Kafka writer that publishes enriched events to the output topic.
     * Uses {@link EnrichedTransactionEvent#getTransactionId()} as the Kafka message key for
     * deterministic partition assignment. {@code setDelete(false)} ensures items are appended,
     * not tombstoned, on the topic.
     *
     * @param kafkaTemplate template configured with the output topic in
     *     {@link com.cominotti.k8sbatch.batch.transaction.adapters.streamingevents.kafka.TransactionKafkaConfig
     *     TransactionKafkaConfig}
     * @return configured Kafka writer
     */
    @Bean
    @StepScope
    public KafkaItemWriter<String, EnrichedTransactionEvent> enrichedTransactionKafkaWriter(
            @Qualifier("transactionKafkaTemplate")
            KafkaTemplate<String, EnrichedTransactionEvent> kafkaTemplate) {
        KafkaItemWriter<String, EnrichedTransactionEvent> writer =
                new KafkaItemWriter<>(EnrichedTransactionEvent::getTransactionId, kafkaTemplate);
        writer.setDelete(false);
        return writer;
    }

    /**
     * Combines the JDBC and Kafka writers into a single {@link CompositeItemWriter} so that each
     * enriched event is written to both MySQL and the Kafka output topic within the same chunk
     * transaction boundary. Delegates are invoked in order: DB write first, then Kafka publish.
     *
     * @param enrichedTransactionDbWriter    MySQL upsert writer
     * @param enrichedTransactionKafkaWriter Kafka output writer
     * @return composite writer that delegates to both writers in order
     */
    @Bean
    @StepScope
    public CompositeItemWriter<EnrichedTransactionEvent> enrichedTransactionCompositeWriter(
            JdbcBatchItemWriter<EnrichedTransactionEvent> enrichedTransactionDbWriter,
            KafkaItemWriter<String, EnrichedTransactionEvent> enrichedTransactionKafkaWriter) {
        CompositeItemWriter<EnrichedTransactionEvent> writer = new CompositeItemWriter<>();
        writer.setDelegates(List.of(enrichedTransactionDbWriter, enrichedTransactionKafkaWriter));
        return writer;
    }

    // ── Step & Job ──────────────────────────────────────────────────

    /**
     * Single chunk step that reads, enriches, and writes transaction events. Not partitioned —
     * parallelism comes from Kafka partition assignment across pod replicas.
     *
     * @param jobRepository                       persists step metadata
     * @param transactionManager                  wraps each chunk in a database transaction
     * @param transactionEventReader              reads Avro events from the input Kafka topic
     * @param transactionEnrichmentProcessor      enriches with exchange rate and risk score
     * @param enrichedTransactionCompositeWriter  writes to both MySQL and Kafka output topic
     * @return the configured enrichment step
     */
    @Bean
    public Step transactionEnrichmentStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            KafkaItemReader<String, TransactionEvent> transactionEventReader,
            TransactionEnrichmentProcessor transactionEnrichmentProcessor,
            CompositeItemWriter<EnrichedTransactionEvent> enrichedTransactionCompositeWriter) {
        return new StepBuilder(BatchStepNames.TRANSACTION_ENRICHMENT_STEP, jobRepository)
                .<TransactionEvent, EnrichedTransactionEvent>chunk(jobProperties.chunkSize())
                .transactionManager(transactionManager)
                .reader(transactionEventReader)
                .processor(transactionEnrichmentProcessor)
                .writer(enrichedTransactionCompositeWriter)
                .listener(stepExecutionListener)
                .build();
    }

    /**
     * Assembles the transaction enrichment job as a simple one-step job with
     * {@link LoggingJobExecutionListener}.
     *
     * @param jobRepository              persists job metadata
     * @param transactionEnrichmentStep  the single enrichment chunk step
     * @return the fully configured {@code transactionEnrichmentJob}
     */
    @Bean
    public Job transactionEnrichmentJob(
            JobRepository jobRepository,
            @Qualifier(BatchStepNames.TRANSACTION_ENRICHMENT_STEP) Step transactionEnrichmentStep) {
        return new JobBuilder(BatchStepNames.TRANSACTION_ENRICHMENT_JOB, jobRepository)
                .listener(jobExecutionListener)
                .start(transactionEnrichmentStep)
                .build();
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static List<Integer> parsePartitions(String partitions) {
        return Arrays.stream(partitions.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .toList();
    }
}
