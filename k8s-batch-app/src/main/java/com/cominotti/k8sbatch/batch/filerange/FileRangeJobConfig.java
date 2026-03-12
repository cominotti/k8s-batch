// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.filerange;

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
 * {@link com.cominotti.k8sbatch.config.RemotePartitioningJobConfig RemotePartitioningJobConfig}
 * (Kafka) or {@link com.cominotti.k8sbatch.batch.standalone.StandaloneJobConfig StandaloneJobConfig}
 * (local threads), depending on the active Spring profile.
 */
@Configuration
public class FileRangeJobConfig {

    private static final Logger log = LoggerFactory.getLogger(FileRangeJobConfig.class);

    private final BatchPartitionProperties partitionProperties;
    private final LoggingJobExecutionListener jobExecutionListener;
    private final LoggingStepExecutionListener stepExecutionListener;

    public FileRangeJobConfig(BatchPartitionProperties partitionProperties,
                              LoggingJobExecutionListener jobExecutionListener,
                              LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.jobExecutionListener = jobExecutionListener;
        this.stepExecutionListener = stepExecutionListener;
        log.info("FileRangeJobConfig initialized | gridSize={} | chunkSize={}",
                partitionProperties.gridSize(), partitionProperties.chunkSize());
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
        return new FileRangePartitioner(new FileSystemResource(inputFile));
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

    /** Worker step: reads CSV lines → filters invalid records → upserts to MySQL, in chunks. */
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
