package com.cominotti.k8sbatch.batch.multifile;

import com.cominotti.k8sbatch.batch.common.CsvRecord;
import com.cominotti.k8sbatch.batch.common.CsvRecordProcessor;
import com.cominotti.k8sbatch.batch.common.CsvRecordReaderFactory;
import com.cominotti.k8sbatch.batch.common.CsvRecordWriter;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;
import java.nio.file.Path;

@Configuration
public class MultiFileJobConfig {

    @Value("${batch.partition.grid-size:4}")
    private int gridSize;

    @Value("${batch.partition.chunk-size:100}")
    private int chunkSize;

    @Bean
    public Job multiFileEtlJob(JobRepository jobRepository, Step multiFileManagerStep) {
        return new JobBuilder("multiFileEtlJob", jobRepository)
                .start(multiFileManagerStep)
                .build();
    }

    @Bean
    public MultiFilePartitioner multiFilePartitioner(
            @Value("${batch.multi-file.input-directory:}") String inputDirectory) {
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
    public CsvRecordProcessor multiFileItemProcessor(
            @Value("#{stepExecutionContext['fileName']}") String fileName) {
        return new CsvRecordProcessor(fileName);
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
                .<CsvRecord, CsvRecord>chunk(chunkSize)
                .transactionManager(transactionManager)
                .reader(multiFileItemReader)
                .processor(multiFileItemProcessor)
                .writer(multiFileItemWriter)
                .build();
    }
}
