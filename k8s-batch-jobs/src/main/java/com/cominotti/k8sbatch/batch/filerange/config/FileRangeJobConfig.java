// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.filerange.config;

import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingJobExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.persistingrecords.jdbc.CsvRecordWriter;
import com.cominotti.k8sbatch.batch.common.adapters.readingcsv.file.CsvRecordReaderFactory;
import com.cominotti.k8sbatch.batch.common.domain.BatchFileProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.domain.CsvRecord;
import com.cominotti.k8sbatch.batch.common.domain.CsvRecordProcessor;
import com.cominotti.k8sbatch.batch.filerange.domain.FileRangePartitioner;
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
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Defines the {@code fileRangeEtlJob} and its worker step for file-range partitioned CSV-to-DB ETL.
 *
 * <p>A single CSV file is split into line-range partitions by {@link FileRangePartitioner}. Each
 * partition's worker step reads its assigned line range, filters invalid records, and upserts to
 * MySQL. The manager step is <strong>not</strong> defined here — it is contributed by either
 * {@link com.cominotti.k8sbatch.config.RemotePartitioningBaseConfig RemotePartitioningBaseConfig}
 * (with a transport sub-profile: Kafka, AMQP, or SQS) or
 * {@link com.cominotti.k8sbatch.config.StandaloneJobConfig StandaloneJobConfig}
 * (local threads), depending on the active Spring profile.
 */
@Configuration
public class FileRangeJobConfig {

    private static final Logger log = LoggerFactory.getLogger(FileRangeJobConfig.class);

    private final BatchPartitionProperties partitionProperties;
    private final BatchFileProperties fileProperties;
    private final LoggingJobExecutionListener jobExecutionListener;
    private final LoggingStepExecutionListener stepExecutionListener;

    /**
     * Injects shared batch infrastructure beans.
     *
     * @param partitionProperties   grid size and chunk size configuration
     * @param fileProperties        allowed base directory for input file validation
     * @param jobExecutionListener  logs job start/end events
     * @param stepExecutionListener logs step start/end events
     */
    public FileRangeJobConfig(BatchPartitionProperties partitionProperties,
                              BatchFileProperties fileProperties,
                              LoggingJobExecutionListener jobExecutionListener,
                              LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.fileProperties = fileProperties;
        this.jobExecutionListener = jobExecutionListener;
        this.stepExecutionListener = stepExecutionListener;
        log.info("FileRangeJobConfig initialized | gridSize={} | chunkSize={} | allowedBaseDir={}",
                partitionProperties.gridSize(), partitionProperties.chunkSize(),
                fileProperties.allowedBaseDir());
    }

    /**
     * Assembles the file-range ETL job by wiring the manager step contributed by the active
     * profile's partition config into a {@link Job} with {@link LoggingJobExecutionListener}.
     *
     * <p>{@code @Qualifier} is required because two {@link Step} beans exist (manager + worker) —
     * Spring cannot resolve by type alone.
     *
     * @param jobRepository         persists job metadata (start time, status, parameters)
     * @param fileRangeManagerStep  manager step injected by
     *     {@link com.cominotti.k8sbatch.config.RemotePartitioningBaseConfig RemotePartitioningBaseConfig}
     *     or {@link com.cominotti.k8sbatch.config.StandaloneJobConfig StandaloneJobConfig}
     * @return the fully configured {@code fileRangeEtlJob}
     */
    @Bean
    public Job fileRangeEtlJob(JobRepository jobRepository, @Qualifier(BatchStepNames.FILE_RANGE_MANAGER_STEP) Step fileRangeManagerStep) {
        return new JobBuilder(BatchStepNames.FILE_RANGE_ETL_JOB, jobRepository)
                .listener(jobExecutionListener)
                .start(fileRangeManagerStep)
                .build();
    }

    // @StepScope defers bean creation until step execution time, enabling SpEL resolution of
    // #{jobParameters[...]} — without it, the expression would be evaluated at context startup
    // when no job parameters are available yet.
    @Bean
    @StepScope
    public FileRangePartitioner fileRangePartitioner(
            // Supplied by the REST API caller in the POST body
            @Value("#{jobParameters['batch.file-range.input-file']}") String inputFile) {
        String safePath = fileProperties.requireWithinAllowedBase(inputFile);
        return new FileRangePartitioner(new FileSystemResource(safePath));
    }

    /**
     * Creates a partition-scoped CSV reader constrained to the line range assigned by
     * {@link FileRangePartitioner}. Each partition reads a different slice of the same file.
     *
     * @param resourcePath CSV file path from the partition's
     *     {@link org.springframework.batch.infrastructure.item.ExecutionContext ExecutionContext}
     * @param startLine    0-based start item index (inclusive, after header skip)
     * @param endLine      0-based end item index (exclusive, after header skip)
     * @return reader for the assigned line range
     */
    @Bean
    @StepScope
    public FlatFileItemReader<CsvRecord> fileRangeItemReader(
            // These keys are populated by FileRangePartitioner — names must match exactly
            @Value("#{stepExecutionContext['resourcePath']}") String resourcePath,
            @Value("#{stepExecutionContext['startLine']}") int startLine,
            @Value("#{stepExecutionContext['endLine']}") int endLine) {
        Resource resource = new FileSystemResource(resourcePath);
        return CsvRecordReaderFactory.createWithLineRange(resource, startLine, endLine);
    }

    /**
     * Creates a partition-scoped processor that filters records with null or blank names.
     *
     * @return stateless processor (scoped to match reader/writer lifecycle)
     */
    @Bean
    @StepScope
    public CsvRecordProcessor fileRangeItemProcessor() {
        return new CsvRecordProcessor();
    }

    /**
     * Creates a partition-scoped JDBC writer tagged with the source file path for traceability.
     * The {@code resourcePath} is written to the {@code source_file} column, enabling queries
     * to trace which partition wrote each row.
     *
     * @param dataSource    MySQL data source
     * @param resourcePath  file path from this partition's
     *     {@link org.springframework.batch.infrastructure.item.ExecutionContext ExecutionContext}
     * @return idempotent writer using {@code ON DUPLICATE KEY UPDATE}
     */
    @Bean
    @StepScope
    public JdbcBatchItemWriter<CsvRecord> fileRangeItemWriter(
            DataSource dataSource,
            @Value("#{stepExecutionContext['resourcePath']}") String resourcePath) {
        return CsvRecordWriter.create(dataSource, resourcePath);
    }

    /**
     * Worker step: reads CSV lines, filters invalid records, and upserts to MySQL in chunks.
     *
     * @param jobRepository          persists step metadata (read/write/filter counts, status)
     * @param transactionManager     wraps each chunk in a database transaction
     * @param fileRangeItemReader    {@code @StepScope} reader constrained to this partition's line range
     * @param fileRangeItemProcessor filters records with null or blank name (returns {@code null} to skip)
     * @param fileRangeItemWriter    idempotent JDBC writer using {@code ON DUPLICATE KEY UPDATE}
     * @return the configured worker {@link Step}
     */
    @Bean
    public Step fileRangeWorkerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<CsvRecord> fileRangeItemReader,
            CsvRecordProcessor fileRangeItemProcessor,
            JdbcBatchItemWriter<CsvRecord> fileRangeItemWriter) {
        return new StepBuilder(BatchStepNames.FILE_RANGE_WORKER_STEP, jobRepository)
                .<CsvRecord, CsvRecord>chunk(partitionProperties.chunkSize())
                .transactionManager(transactionManager)
                .reader(fileRangeItemReader)
                .processor(fileRangeItemProcessor)
                .writer(fileRangeItemWriter)
                .listener(stepExecutionListener)
                .build();
    }
}
