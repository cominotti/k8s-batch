package com.cominotti.k8sbatch.batch.standalone;

import com.cominotti.k8sbatch.batch.common.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.filerange.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.MultiFilePartitioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(StandaloneJobConfig.class);

    private final BatchPartitionProperties partitionProperties;
    private final LoggingStepExecutionListener stepExecutionListener;

    public StandaloneJobConfig(BatchPartitionProperties partitionProperties,
                               LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.stepExecutionListener = stepExecutionListener;
    }

    @Bean
    public Step fileRangeManagerStep(
            JobRepository jobRepository,
            FileRangePartitioner fileRangePartitioner,
            @Qualifier(BatchStepNames.FILE_RANGE_WORKER_STEP) Step fileRangeWorkerStep) {
        return buildStandaloneManagerStep(jobRepository, BatchStepNames.FILE_RANGE_MANAGER_STEP,
                BatchStepNames.FILE_RANGE_WORKER_STEP, fileRangePartitioner,
                fileRangeWorkerStep, "file-range-");
    }

    @Bean
    public Step multiFileManagerStep(
            JobRepository jobRepository,
            MultiFilePartitioner multiFilePartitioner,
            @Qualifier(BatchStepNames.MULTI_FILE_WORKER_STEP) Step multiFileWorkerStep) {
        return buildStandaloneManagerStep(jobRepository, BatchStepNames.MULTI_FILE_MANAGER_STEP,
                BatchStepNames.MULTI_FILE_WORKER_STEP, multiFilePartitioner,
                multiFileWorkerStep, "multi-file-");
    }

    // Note: TaskExecutorPartitionHandler does not expose a timeout API.
    // In integration tests, the JUnit @Timeout annotation serves as the backstop
    // to prevent indefinite hangs if a worker step blocks.
    private Step buildStandaloneManagerStep(
            JobRepository jobRepository, String managerStepName, String workerStepName,
            org.springframework.batch.core.partition.Partitioner partitioner,
            Step workerStep, String threadPrefix) {
        TaskExecutorPartitionHandler handler = new TaskExecutorPartitionHandler();
        handler.setStep(workerStep);
        handler.setTaskExecutor(partitionTaskExecutor(threadPrefix));
        handler.setGridSize(partitionProperties.gridSize());

        log.info("Configuring standalone manager step '{}' | gridSize={}",
                managerStepName, partitionProperties.gridSize());

        return new StepBuilder(managerStepName, jobRepository)
                .partitioner(workerStepName, partitioner)
                .partitionHandler(handler)
                .listener(stepExecutionListener)
                .build();
    }

    private ThreadPoolTaskExecutor partitionTaskExecutor(String threadNamePrefix) {
        log.info("Creating partition thread pool | prefix={} | poolSize={}",
                threadNamePrefix, partitionProperties.gridSize());
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(partitionProperties.gridSize());
        executor.setMaxPoolSize(partitionProperties.gridSize());
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.initialize();
        return executor;
    }
}
