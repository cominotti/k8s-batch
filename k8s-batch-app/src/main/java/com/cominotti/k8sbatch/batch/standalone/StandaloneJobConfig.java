package com.cominotti.k8sbatch.batch.standalone;

import com.cominotti.k8sbatch.batch.filerange.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.MultiFilePartitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

@Configuration
@Profile("standalone")
public class StandaloneJobConfig {

    @Value("${batch.partition.grid-size:4}")
    private int gridSize;

    @Bean
    public Step fileRangeManagerStep(
            JobRepository jobRepository,
            FileRangePartitioner fileRangePartitioner,
            Step fileRangeWorkerStep) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(fileRangeWorkerStep);
        handler.setTaskExecutor(new SimpleAsyncTaskExecutor("file-range-"));
        handler.setGridSize(gridSize);

        return new StepBuilder("fileRangeManagerStep", jobRepository)
                .partitioner("fileRangeWorkerStep", fileRangePartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    public Step multiFileManagerStep(
            JobRepository jobRepository,
            MultiFilePartitioner multiFilePartitioner,
            Step multiFileWorkerStep) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(multiFileWorkerStep);
        handler.setTaskExecutor(new SimpleAsyncTaskExecutor("multi-file-"));
        handler.setGridSize(gridSize);

        return new StepBuilder("multiFileManagerStep", jobRepository)
                .partitioner("multiFileWorkerStep", multiFilePartitioner)
                .partitionHandler(handler)
                .build();
    }
}
