// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.multifile.config;

import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingJobExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.persistingrecords.jdbc.CsvRecordWriter;
import com.cominotti.k8sbatch.batch.common.adapters.readingcsv.file.CsvRecordReaderFactory;
import com.cominotti.k8sbatch.batch.common.domain.BatchFileProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.domain.CsvRecord;
import com.cominotti.k8sbatch.batch.common.domain.CsvRecordProcessor;
import com.cominotti.k8sbatch.batch.multifile.domain.MultiFilePartitioner;
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
 * {@link com.cominotti.k8sbatch.config.RemotePartitioningJobConfig RemotePartitioningJobConfig}
 * (Kafka) or {@link com.cominotti.k8sbatch.config.StandaloneJobConfig StandaloneJobConfig}
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

    // @Qualifier is required because two Step beans exist (manager + worker) — Spring can't
    // resolve by type alone. The constant must match the bean name in the manager config.
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
        String safePath = requireWithinAllowedBase(inputDirectory, fileProperties.allowedBaseDir());
        return new MultiFilePartitioner(Path.of(safePath));
    }

    // @StepScope: new reader per partition, each reading a different CSV file
    @Bean
    @StepScope
    public FlatFileItemReader<CsvRecord> multiFileItemReader(
            // Key populated by MultiFilePartitioner — name must match exactly
            @Value("#{stepExecutionContext['filePath']}") String filePath) {
        return CsvRecordReaderFactory.create(filePath);
    }

    // @StepScope: new processor per partition (stateless, but scope matches reader/writer)
    @Bean
    @StepScope
    public CsvRecordProcessor multiFileItemProcessor() {
        return new CsvRecordProcessor();
    }

    // @StepScope: new writer per partition, tagged with just the file name (not full path)
    // for traceability in the source_file column
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

    /**
     * Validates that {@code inputPath} resolves to a location within {@code allowedBaseDir},
     * preventing path traversal attacks (CWE-22). Returns the normalised absolute path string.
     *
     * @param inputPath      user-supplied directory path from job parameters
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
