package com.cominotti.k8sbatch.batch.multifile;

import com.cominotti.k8sbatch.batch.common.BatchPartitionProperties;
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

@Configuration
public class MultiFileJobConfig {

    private static final Logger log = LoggerFactory.getLogger(MultiFileJobConfig.class);

    private final BatchPartitionProperties partitionProperties;
    private final LoggingJobExecutionListener jobExecutionListener;
    private final LoggingStepExecutionListener stepExecutionListener;

    public MultiFileJobConfig(BatchPartitionProperties partitionProperties,
                              LoggingJobExecutionListener jobExecutionListener,
                              LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.jobExecutionListener = jobExecutionListener;
        this.stepExecutionListener = stepExecutionListener;
        log.info("MultiFileJobConfig initialized | gridSize={} | chunkSize={}",
                partitionProperties.gridSize(), partitionProperties.chunkSize());
    }

    @Bean
    public Job multiFileEtlJob(JobRepository jobRepository, @Qualifier("multiFileManagerStep") Step multiFileManagerStep) {
        return new JobBuilder("multiFileEtlJob", jobRepository)
                .listener(jobExecutionListener)
                .start(multiFileManagerStep)
                .build();
    }

    @Bean
    @StepScope
    public MultiFilePartitioner multiFilePartitioner(
            @Value("#{jobParameters['batch.multi-file.input-directory']}") String inputDirectory) {
        return new MultiFilePartitioner(Path.of(inputDirectory));
    }

    @Bean
    @StepScope
    public FlatFileItemReader<CsvRecord> multiFileItemReader(
            @Value("#{stepExecutionContext['filePath']}") String filePath) {
        return CsvRecordReaderFactory.create(filePath);
    }

    @Bean
    @StepScope
    public CsvRecordProcessor multiFileItemProcessor() {
        return new CsvRecordProcessor();
    }

    @Bean
    @StepScope
    public JdbcBatchItemWriter<CsvRecord> multiFileItemWriter(
            DataSource dataSource,
            @Value("#{stepExecutionContext['fileName']}") String fileName) {
        return CsvRecordWriter.create(dataSource, fileName);
    }

    @Bean
    public Step multiFileWorkerStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<CsvRecord> multiFileItemReader,
            CsvRecordProcessor multiFileItemProcessor,
            JdbcBatchItemWriter<CsvRecord> multiFileItemWriter) {
        return new StepBuilder("multiFileWorkerStep", jobRepository)
                .<CsvRecord, CsvRecord>chunk(partitionProperties.chunkSize())
                .transactionManager(transactionManager)
                .reader(multiFileItemReader)
                .processor(multiFileItemProcessor)
                .writer(multiFileItemWriter)
                .listener(stepExecutionListener)
                .build();
    }
}
