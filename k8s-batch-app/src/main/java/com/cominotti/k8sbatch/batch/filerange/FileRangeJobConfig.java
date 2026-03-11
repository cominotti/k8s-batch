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

    @Bean
    public Job fileRangeEtlJob(JobRepository jobRepository, @Qualifier(BatchStepNames.FILE_RANGE_MANAGER_STEP) Step fileRangeManagerStep) {
        return new JobBuilder(BatchStepNames.FILE_RANGE_ETL_JOB, jobRepository)
                .listener(jobExecutionListener)
                .start(fileRangeManagerStep)
                .build();
    }

    @Bean
    @StepScope
    public FileRangePartitioner fileRangePartitioner(
            @Value("#{jobParameters['batch.file-range.input-file']}") String inputFile) {
        return new FileRangePartitioner(new FileSystemResource(inputFile));
    }

    @Bean
    @StepScope
    public FlatFileItemReader<CsvRecord> fileRangeItemReader(
            @Value("#{stepExecutionContext['resourcePath']}") String resourcePath,
            @Value("#{stepExecutionContext['startLine']}") int startLine,
            @Value("#{stepExecutionContext['endLine']}") int endLine) {
        Resource resource = new FileSystemResource(resourcePath);
        return CsvRecordReaderFactory.createWithLineRange(resource, startLine, endLine);
    }

    @Bean
    @StepScope
    public CsvRecordProcessor fileRangeItemProcessor() {
        return new CsvRecordProcessor();
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<CsvRecord> fileRangeItemWriter(
            DataSource dataSource,
            @Value("#{stepExecutionContext['resourcePath']}") String resourcePath) {
        return CsvRecordWriter.create(dataSource, resourcePath);
    }

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
