// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.multifile;

import com.cominotti.k8sbatch.batch.common.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.CsvRecord;
import com.cominotti.k8sbatch.batch.common.CsvRecordProcessor;
import com.cominotti.k8sbatch.batch.common.CsvRecordReaderFactory;
import com.cominotti.k8sbatch.batch.common.CsvRecordWriter;
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
 * (Kafka) or {@link com.cominotti.k8sbatch.batch.standalone.StandaloneJobConfig StandaloneJobConfig}
 * (local threads), depending on the active Spring profile.
 */
@Configuration
public class MultiFileJobConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiFileJobConfig.class);

    private final BatchPartitionProperties partitionProperties;
    private final LoggingJobExecutionListener jobExecutionListener;
    private final LoggingStepExecutionListener stepExecutionListener;

    /**
     * Injects shared batch infrastructure beans.
     *
     * @param partitionProperties grid size and chunk size configuration
     * @param jobExecutionListener logs job start/end events
     * @param stepExecutionListener logs step start/end events
     */
    public MultiFileJobConfig(BatchPartitionProperties partitionProperties,
                              LoggingJobExecutionListener jobExecutionListener,
                              LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.jobExecutionListener = jobExecutionListener;
        this.stepExecutionListener = stepExecutionListener;
        log.info("MultiFileJobConfig initialized | gridSize={} | chunkSize={}",
                partitionProperties.gridSize(), partitionProperties.chunkSize());
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
        return new MultiFilePartitioner(Path.of(inputDirectory));
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
     * @param jobRepository        persists step metadata (read/write/filter counts, status)
     * @param transactionManager   wraps each chunk in a database transaction
     * @param multiFileItemReader  {@code @StepScope} reader for this partition's CSV file
     * @param multiFileItemProcessor filters records with null or blank name (returns {@code null} to skip)
     * @param multiFileItemWriter  idempotent JDBC writer using {@code ON DUPLICATE KEY UPDATE}
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
