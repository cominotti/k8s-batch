package com.cominotti.k8sbatch.batch.standalone;

import com.cominotti.k8sbatch.batch.common.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.filerange.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.MultiFilePartitioner;
import org.springframework.batch.core.partition.support.TaskExecutorPartitionHandler;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@Profile("standalone")
public class StandaloneJobConfig {

    private final BatchPartitionProperties partitionProperties;

    public StandaloneJobConfig(BatchPartitionProperties partitionProperties) {
        this.partitionProperties = partitionProperties;
    }

    @Bean
    public Step fileRangeManagerStep(
            JobRepository jobRepository,
            FileRangePartitioner fileRangePartitioner,
            @Qualifier("fileRangeWorkerStep") Step fileRangeWorkerStep) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(fileRangeWorkerStep);
        handler.setTaskExecutor(partitionTaskExecutor("file-range-"));
        handler.setGridSize(partitionProperties.gridSize());

        return new StepBuilder("fileRangeManagerStep", jobRepository)
                .partitioner("fileRangeWorkerStep", fileRangePartitioner)
                .partitionHandler(handler)
                .build();
    }

    @Bean
    public Step multiFileManagerStep(
            JobRepository jobRepository,
            MultiFilePartitioner multiFilePartitioner,
            @Qualifier("multiFileWorkerStep") Step multiFileWorkerStep) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(multiFileWorkerStep);
        handler.setTaskExecutor(partitionTaskExecutor("multi-file-"));
        handler.setGridSize(partitionProperties.gridSize());

        return new StepBuilder("multiFileManagerStep", jobRepository)
                .partitioner("multiFileWorkerStep", multiFilePartitioner)
                .partitionHandler(handler)
                .build();
    }

    private ThreadPoolTaskExecutor partitionTaskExecutor(String threadNamePrefix) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(partitionProperties.gridSize());
        executor.setMaxPoolSize(partitionProperties.gridSize());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.initialize();
        return executor;
    }
}
