// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.config;

import com.cominotti.k8sbatch.batch.common.domain.BatchPartitionProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.filerange.domain.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.domain.MultiFilePartitioner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.integration.partition.BeanFactoryStepLocator;
import org.springframework.batch.integration.partition.RemotePartitioningManagerStepBuilderFactory;
import org.springframework.batch.integration.partition.StepExecutionRequestHandler;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;

/**
 * Shared infrastructure for all remote partitioning transports (Kafka, AMQP, SQS).
 *
 * <p>Provides transport-agnostic beans: the {@link RemotePartitioningManagerStepBuilderFactory},
 * the {@link DirectChannel} that bridges manager steps to outbound message flows, the
 * {@link StepExecutionRequestHandler} that executes worker steps from inbound messages, and the
 * manager step beans for each partitioned job.
 *
 * <p>Activated under the {@code remote-partitioning} profile — paired with a transport-specific
 * sub-profile ({@code remote-kafka} or {@code remote-jms}) that provides the outbound and
 * inbound {@code IntegrationFlow} beans. When the {@code standalone} profile is active,
 * {@link StandaloneJobConfig} provides manager steps instead — the two are mutually exclusive.
 *
 * @see RemotePartitioningKafkaConfig
 * @see RemotePartitioningJmsConfig
 */
@Configuration
@Profile("remote-partitioning")
public class RemotePartitioningBaseConfig {

    private static final Logger log = LoggerFactory.getLogger(RemotePartitioningBaseConfig.class);

    /**
     * Deserialization whitelist for {@code StepExecutionRequest} messages received from the
     * message broker. Restricts Java deserialization to Spring Batch and JDK classes to prevent
     * deserialization gadget attacks. Shared by all transport configs (Kafka, JMS).
     */
    public static final String[] DESERIALIZATION_ALLOWLIST = {
            "org.springframework.batch.*", "java.util.*", "java.lang.*"
    };

    private final BatchPartitionProperties partitionProperties;
    private final LoggingStepExecutionListener stepExecutionListener;

    /**
     * Injects shared batch infrastructure.
     *
     * @param partitionProperties   grid size, chunk size, and polling timeout for manager steps
     * @param stepExecutionListener logs step start/end events on manager steps
     */
    public RemotePartitioningBaseConfig(
            BatchPartitionProperties partitionProperties,
            LoggingStepExecutionListener stepExecutionListener) {
        this.partitionProperties = partitionProperties;
        this.stepExecutionListener = stepExecutionListener;
    }

    // ── Builder Factory ──────────────────────────────────────────

    /**
     * Creates the factory for building remote partitioning manager steps. Used by
     * {@link #fileRangeManagerStep} and {@link #multiFileManagerStep}.
     *
     * @param jobRepository repository for persisting step execution metadata
     * @return factory for building manager steps with remote partitioning support
     */
    @Bean
    public RemotePartitioningManagerStepBuilderFactory managerStepBuilderFactory(
            JobRepository jobRepository) {
        return new RemotePartitioningManagerStepBuilderFactory(jobRepository);
    }

    // ── Channel ──────────────────────────────────────────────────

    /**
     * Spring Integration channel that receives {@code StepExecutionRequest} messages from the
     * manager step and routes them to the transport-specific outbound flow (Kafka or JMS).
     *
     * @return direct channel (synchronous — processes messages on the caller's thread)
     */
    @Bean
    public DirectChannel managerRequestsChannel() {
        return new DirectChannel();
    }

    // ── Worker Handler ───────────────────────────────────────────

    /**
     * Worker-side handler that receives deserialized {@code StepExecutionRequest} messages,
     * resolves the target {@link org.springframework.batch.core.step.Step Step} bean by name via
     * {@link BeanFactoryStepLocator}, and executes it. Step names must match bean names defined
     * in {@link com.cominotti.k8sbatch.batch.common.domain.BatchStepNames BatchStepNames}.
     *
     * @param jobRepository repository for persisting step execution status
     * @param beanFactory   Spring bean factory for resolving step beans by name
     * @return configured handler for worker-side step execution
     */
    @Bean
    public StepExecutionRequestHandler stepExecutionRequestHandler(
            JobRepository jobRepository, BeanFactory beanFactory) {
        log.info("Configuring StepExecutionRequestHandler for worker-side processing");
        StepExecutionRequestHandler handler = new StepExecutionRequestHandler();
        handler.setJobRepository(jobRepository);
        BeanFactoryStepLocator stepLocator = new BeanFactoryStepLocator();
        stepLocator.setBeanFactory(beanFactory);
        handler.setStepLocator(stepLocator);
        return handler;
    }

    // ── Manager Steps ────────────────────────────────────────────

    /**
     * Remote partitioning manager step for the file-range ETL job. Publishes partition requests
     * to the transport-specific outbound channel and polls {@link JobRepository} until all workers
     * complete or timeout expires.
     *
     * @param factory              builder factory for remote manager steps
     * @param fileRangePartitioner splits the CSV file into line-range partitions
     * @return configured manager step with outbound channel and polling timeout
     */
    @Bean
    public Step fileRangeManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            FileRangePartitioner fileRangePartitioner) {
        return buildRemoteManagerStep(factory, BatchStepNames.FILE_RANGE_MANAGER_STEP,
                BatchStepNames.FILE_RANGE_WORKER_STEP, fileRangePartitioner);
    }

    /**
     * Remote partitioning manager step for the multi-file ETL job. Publishes partition requests
     * to the transport-specific outbound channel and polls {@link JobRepository} until all workers
     * complete or timeout expires.
     *
     * @param factory              builder factory for remote manager steps
     * @param multiFilePartitioner assigns one CSV file per partition
     * @return configured manager step with outbound channel and polling timeout
     */
    @Bean
    public Step multiFileManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            MultiFilePartitioner multiFilePartitioner) {
        return buildRemoteManagerStep(factory, BatchStepNames.MULTI_FILE_MANAGER_STEP,
                BatchStepNames.MULTI_FILE_WORKER_STEP, multiFilePartitioner);
    }

    /**
     * Shared builder for remote manager steps. Configures partitioning, grid size, the outbound
     * channel (transport-agnostic {@link DirectChannel}), polling timeout, and the step execution
     * listener. No reply channel is used — the manager detects worker completion by polling
     * {@link JobRepository}, which avoids fragile {@code StepExecution} serialization through
     * the message broker.
     */
    private Step buildRemoteManagerStep(
            RemotePartitioningManagerStepBuilderFactory factory,
            String managerStepName, String workerStepName,
            org.springframework.batch.core.partition.Partitioner partitioner) {
        log.info("Configuring remote manager step '{}' | gridSize={} | timeout={}ms",
                managerStepName, partitionProperties.gridSize(), partitionProperties.timeoutMs());
        return factory.get(managerStepName)
                .partitioner(workerStepName, partitioner)
                .gridSize(partitionProperties.gridSize())
                .outputChannel(managerRequestsChannel())
                .timeout(partitionProperties.timeoutMs())
                .listener(stepExecutionListener)
                .build();
    }
}
