// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.multifile.config;

import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingJobExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.persistingrecords.jdbc.CsvRecordWriter;
import com.cominotti.k8sbatch.batch.common.adapters.readingcsv.file.CsvRecordReaderFactory;
import com.cominotti.k8sbatch.batch.common.config.BatchFileProperties;
import com.cominotti.k8sbatch.batch.common.config.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.domain.CsvRecord;
import com.cominotti.k8sbatch.batch.common.config.CsvRecordProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;

/**
 * Defines the {@code multiFileEtlJob} and its worker step for multi-file partitioned CSV-to-DB ETL.
 *
 * <p>Each CSV file in a directory becomes one partition via {@link MultiFilePartitioner}. Each
 * partition's worker step reads its entire file, filters invalid records, and upserts to MySQL.
 * The manager step is <strong>not</strong> defined here — it is contributed by either
 * {@link com.cominotti.k8sbatch.config.RemotePartitioningBaseConfig RemotePartitioningBaseConfig}
 * (with a transport sub-profile: Kafka, AMQP, or SQS) or
 * {@link com.cominotti.k8sbatch.config.StandaloneJobConfig StandaloneJobConfig}
 * (local threads), depending on the active Spring profile.
 */
@Configuration
public class MultiFileJobConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiFileJobConfig.class);

    private final BatchPartitionProperties partitionProperties;
    private final BatchFileProperties fileProperties;
    private final LoggingJobExecutionListener jobExecutionListener;
    private final LoggingStepExecutionListener stepExecutionListener;

    /**
     * Injects shared batch infrastructure beans.
     *
     * @param partitionProperties   grid size and chunk size configuration
     * @param fileProperties        allowed base directory for input directory validation
     * @param jobExecutionListener  logs job start/end events
     * @param stepExecutionListener logs step start/end events
     */
    public MultiFileJobConfig(BatchPartitionProperties partitionProperties,
                              BatchFileProperties fileProperties,
                              LoggingJobExecutionListener jobExecutionListener,
                              LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.fileProperties = fileProperties;
        this.jobExecutionListener = jobExecutionListener;
        this.stepExecutionListener = stepExecutionListener;
        log.info("MultiFileJobConfig initialized | gridSize={} | chunkSize={} | allowedBaseDir={}",
                partitionProperties.gridSize(), partitionProperties.chunkSize(),
                fileProperties.allowedBaseDir());
    }

    /**
     * Assembles the multi-file ETL job by wiring the manager step contributed by the active
     * profile's partition config into a {@link Job} with {@link LoggingJobExecutionListener}.
     *
     * <p>{@code @Qualifier} is required because two {@link Step} beans exist (manager + worker) —
     * Spring cannot resolve by type alone.
     *
     * @param jobRepository        persists job metadata (start time, status, parameters)
     * @param multiFileManagerStep manager step injected by
     *     {@link com.cominotti.k8sbatch.config.RemotePartitioningBaseConfig RemotePartitioningBaseConfig}
     *     or {@link com.cominotti.k8sbatch.config.StandaloneJobConfig StandaloneJobConfig}
     * @return the fully configured {@code multiFileEtlJob}
     */
    @Bean
    public Job multiFileEtlJob(JobRepository jobRepository, @Qualifier(BatchStepNames.MULTI_FILE_MANAGER_STEP) Step multiFileManagerStep) {
        return new JobBuilder(BatchStepNames.MULTI_FILE_ETL_JOB, jobRepository)
                .listener(jobExecutionListener)
                .start(multiFileManagerStep)
                .build();
    }

    // @StepScope defers bean creation until step execution time, enabling SpEL resolution of
    // #{jobParameters[...]} — without it, the expression would be evaluated at context startup
    // when no job parameters are available yet.
    @Bean
    @StepScope
    public MultiFilePartitioner multiFilePartitioner(
            // Supplied by the REST API caller in the POST body
            @Value("#{jobParameters['batch.multi-file.input-directory']}") String inputDirectory) {
        String safePath = fileProperties.requireWithinAllowedBase(inputDirectory);
        return new MultiFilePartitioner(Path.of(safePath));
    }

    /**
     * Creates a partition-scoped CSV reader for this partition's assigned file.
     *
     * @param filePath file path from the partition's
     *     {@link org.springframework.batch.infrastructure.item.ExecutionContext ExecutionContext},
     *     populated by {@link MultiFilePartitioner}
     * @return reader for the full CSV file (all data lines)
     */
    @Bean
    @StepScope
    public FlatFileItemReader<CsvRecord> multiFileItemReader(
            // Key populated by MultiFilePartitioner — name must match exactly
            @Value("#{stepExecutionContext['filePath']}") String filePath) {
        return CsvRecordReaderFactory.create(filePath);
    }

    /**
     * Creates a partition-scoped processor that filters records with null or blank names.
     *
     * @return stateless processor (scoped to match reader/writer lifecycle)
     */
    @Bean
    @StepScope
    public CsvRecordProcessor multiFileItemProcessor() {
        return new CsvRecordProcessor();
    }

    /**
     * Creates a partition-scoped JDBC writer tagged with the file name for traceability.
     * The {@code fileName} (not full path) is written to the {@code source_file} column.
     *
     * @param dataSource MySQL data source
     * @param fileName   file name from this partition's
     *     {@link org.springframework.batch.infrastructure.item.ExecutionContext ExecutionContext}
     * @return idempotent writer using {@code ON DUPLICATE KEY UPDATE}
     */
    @Bean
    @StepScope
    public JdbcBatchItemWriter<CsvRecord> multiFileItemWriter(
            DataSource dataSource,
            // Uses 'fileName' (just the name) while reader uses 'filePath' (full path)
            @Value("#{stepExecutionContext['fileName']}") String fileName) {
        return CsvRecordWriter.create(dataSource, fileName);
    }

    /**
     * Worker step: reads an entire CSV file, filters invalid records, and upserts to MySQL in chunks.
     *
     * @param jobRepository          persists step metadata (read/write/filter counts, status)
     * @param transactionManager     wraps each chunk in a database transaction
     * @param multiFileItemReader    {@code @StepScope} reader for this partition's CSV file
     * @param multiFileItemProcessor filters records with null or blank name (returns {@code null} to skip)
     * @param multiFileItemWriter    idempotent JDBC writer using {@code ON DUPLICATE KEY UPDATE}
     * @return the configured worker {@link Step}
     */
    @Bean
    public Step multiFileWorkerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<CsvRecord> multiFileItemReader,
            CsvRecordProcessor multiFileItemProcessor,
            JdbcBatchItemWriter<CsvRecord> multiFileItemWriter) {
        return new StepBuilder(BatchStepNames.MULTI_FILE_WORKER_STEP, jobRepository)
                .<CsvRecord, CsvRecord>chunk(partitionProperties.chunkSize())
                .transactionManager(transactionManager)
                .reader(multiFileItemReader)
                .processor(multiFileItemProcessor)
                .writer(multiFileItemWriter)
                .listener(stepExecutionListener)
                .build();
    }
}
