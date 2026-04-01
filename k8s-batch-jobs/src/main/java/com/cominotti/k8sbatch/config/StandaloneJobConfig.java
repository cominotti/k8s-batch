// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.config;

import com.cominotti.k8sbatch.batch.common.config.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.filerange.config.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.config.MultiFilePartitioner;
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

/**
 * Provides in-process parallel partition execution as an alternative to Kafka-based remote
 * partitioning. Uses {@link TaskExecutorPartitionHandler} with a thread pool to run worker steps
 * locally — no Kafka dependency required.
 *
 * <p>Activated only under the {@code standalone} profile. When absent, the default
 * {@code remote-partitioning} profile activates
 * {@link RemotePartitioningBaseConfig}
 * instead — the two configs are mutually exclusive.
 */
@Configuration
@Profile("standalone")
public class StandaloneJobConfig {

    private static final Logger log = LoggerFactory.getLogger(StandaloneJobConfig.class);

    private final BatchPartitionProperties partitionProperties;
    private final LoggingStepExecutionListener stepExecutionListener;

    /**
     * Injects partition properties and the step listener used by the manager steps.
     *
     * @param partitionProperties grid size configuration (also used as thread pool size)
     * @param stepExecutionListener logs step start/end events
     */
    public StandaloneJobConfig(BatchPartitionProperties partitionProperties,
                               LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.stepExecutionListener = stepExecutionListener;
    }

    /**
     * Manager step for file-range partitioning in standalone mode. Distributes partitions across
     * local threads instead of Kafka-connected worker pods.
     *
     * @param jobRepository         persists step execution metadata
     * @param fileRangePartitioner  splits the CSV file into line-range partitions
     * @param fileRangeWorkerStep   worker step that processes one partition
     * @return configured manager step using {@link TaskExecutorPartitionHandler}
     */
    @Bean
    public Step fileRangeManagerStep(
            JobRepository jobRepository,
            FileRangePartitioner fileRangePartitioner,
            @Qualifier(BatchStepNames.FILE_RANGE_WORKER_STEP) Step fileRangeWorkerStep) {
        return buildStandaloneManagerStep(jobRepository, BatchStepNames.FILE_RANGE_MANAGER_STEP,
                BatchStepNames.FILE_RANGE_WORKER_STEP, fileRangePartitioner,
                fileRangeWorkerStep, "file-range-");
    }

    /**
     * Manager step for multi-file partitioning in standalone mode. Distributes partitions across
     * local threads instead of Kafka-connected worker pods.
     *
     * @param jobRepository          persists step execution metadata
     * @param multiFilePartitioner   assigns one CSV file per partition
     * @param multiFileWorkerStep    worker step that processes one partition's file
     * @return configured manager step using {@link TaskExecutorPartitionHandler}
     */
    @Bean
    public Step multiFileManagerStep(
            JobRepository jobRepository,
            MultiFilePartitioner multiFilePartitioner,
            @Qualifier(BatchStepNames.MULTI_FILE_WORKER_STEP) Step multiFileWorkerStep) {
        return buildStandaloneManagerStep(jobRepository, BatchStepNames.MULTI_FILE_MANAGER_STEP,
                BatchStepNames.MULTI_FILE_WORKER_STEP, multiFilePartitioner,
                multiFileWorkerStep, "multi-file-");
    }

    /**
     * Builds a standalone manager step using {@link TaskExecutorPartitionHandler} to run worker
     * steps in parallel threads within the same JVM.
     *
     * <p><strong>Note:</strong> {@code TaskExecutorPartitionHandler} does not expose a timeout
     * API. In integration tests, JUnit's {@code @Timeout} annotation serves as the backstop to
     * prevent indefinite hangs if a worker step deadlocks.
     */
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

    /**
     * Creates a new thread pool per call (not a shared bean) so each job gets a distinct thread
     * name prefix for log correlation (e.g. {@code file-range-1} vs {@code multi-file-1}).
     * Pool size equals grid size so all partitions can execute concurrently.
     */
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
