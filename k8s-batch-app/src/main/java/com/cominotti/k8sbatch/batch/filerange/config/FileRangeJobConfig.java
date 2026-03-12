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
import java.nio.file.Path;

/**
 * Defines the {@code fileRangeEtlJob} and its worker step for file-range partitioned CSV-to-DB ETL.
 *
 * <p>A single CSV file is split into line-range partitions by {@link FileRangePartitioner}. Each
 * partition's worker step reads its assigned line range, filters invalid records, and upserts to
 * MySQL. The manager step is <strong>not</strong> defined here — it is contributed by either
 * {@link com.cominotti.k8sbatch.config.RemotePartitioningJobConfig RemotePartitioningJobConfig}
 * (Kafka) or {@link com.cominotti.k8sbatch.config.StandaloneJobConfig StandaloneJobConfig}
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

    // @Qualifier is required because two Step beans exist (manager + worker) — Spring can't
    // resolve by type alone. The constant must match the bean name in the manager config.
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
        String safePath = requireWithinAllowedBase(inputFile, fileProperties.allowedBaseDir());
        return new FileRangePartitioner(new FileSystemResource(safePath));
    }

    // @StepScope: new reader per partition with its own line range from the ExecutionContext
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

    // @StepScope: new processor per partition (stateless, but scope matches reader/writer)
    @Bean
    @StepScope
    public CsvRecordProcessor fileRangeItemProcessor() {
        return new CsvRecordProcessor();
    }

    // @StepScope: new writer per partition, tagged with the partition's source file for traceability
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

    /**
     * Validates that {@code inputPath} resolves to a location within {@code allowedBaseDir},
     * preventing path traversal attacks (CWE-22). Returns the normalised absolute path string.
     *
     * @param inputPath      user-supplied file path from job parameters
     * @param allowedBaseDir configured base directory boundary (e.g. {@code /data})
     * @return normalised absolute path, guaranteed to reside within {@code allowedBaseDir}
     * @throws IllegalArgumentException if the resolved path escapes the allowed base
     */
    private static String requireWithinAllowedBase(String inputPath, String allowedBaseDir) {
        Path base = Path.of(allowedBaseDir).toAbsolutePath().normalize();
        Path resolved = base.resolve(inputPath).normalize().toAbsolutePath();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException(
                    "Input path is not within the allowed base directory | path=" + inputPath
                            + " | allowedBaseDir=" + allowedBaseDir);
        }
        return resolved.toString();
    }
}
