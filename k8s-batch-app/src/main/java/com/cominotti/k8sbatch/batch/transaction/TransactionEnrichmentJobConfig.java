// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction;

import com.cominotti.k8sbatch.avro.EnrichedTransactionEvent;
import com.cominotti.k8sbatch.avro.TransactionEvent;
import com.cominotti.k8sbatch.batch.common.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.LoggingJobExecutionListener;
import com.cominotti.k8sbatch.batch.common.LoggingStepExecutionListener;
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
@Profile("remote-partitioning")
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

    // @StepScope enables late binding of job parameters for partition selection.
    // KafkaItemReader requires explicit partition-to-offset mappings — it does not use
    // the consumer group protocol for partition assignment.
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
