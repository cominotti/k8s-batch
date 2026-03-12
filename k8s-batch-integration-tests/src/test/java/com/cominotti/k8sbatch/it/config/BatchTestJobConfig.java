// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.config;

import com.cominotti.k8sbatch.batch.common.BatchStepNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.batch.test.JobRepositoryTestUtils;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;

/**
 * Provides test utilities for launching and cleaning up batch jobs in integration tests.
 *
 * <p>Two {@link JobOperatorTestUtils} beans are needed (one per job) because each instance is
 * bound to a single {@code Job} via {@code setJob()} — this is the Spring Batch 6.x API
 * (replaces the deprecated {@code JobLauncherTestUtils}).
 * {@link org.springframework.batch.test.JobRepositoryTestUtils} handles metadata cleanup
 * via {@code removeJobExecutions()}, which cascades through all {@code BATCH_*} tables.
 */
@TestConfiguration(proxyBeanMethods = false)
public class BatchTestJobConfig {

    private static final Logger log = LoggerFactory.getLogger(BatchTestJobConfig.class);

    @Bean
    public JobOperatorTestUtils fileRangeJobOperatorTestUtils(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier(BatchStepNames.FILE_RANGE_ETL_JOB) Job fileRangeEtlJob) {
        log.debug("Creating JobOperatorTestUtils for fileRangeEtlJob");
        JobOperatorTestUtils utils = new JobOperatorTestUtils(jobOperator, jobRepository);
        utils.setJob(fileRangeEtlJob);
        return utils;
    }

    @Bean
    public JobOperatorTestUtils multiFileJobOperatorTestUtils(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier(BatchStepNames.MULTI_FILE_ETL_JOB) Job multiFileEtlJob) {
        log.debug("Creating JobOperatorTestUtils for multiFileEtlJob");
        JobOperatorTestUtils utils = new JobOperatorTestUtils(jobOperator, jobRepository);
        utils.setJob(multiFileEtlJob);
        return utils;
    }

    // Only created under remote-partitioning profile — standalone tests don't load the
    // transactionEnrichmentJob bean, so this must be profile-gated to avoid NoSuchBeanException.
    @Bean
    @Profile("remote-partitioning")
    public JobOperatorTestUtils transactionJobOperatorTestUtils(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier(BatchStepNames.TRANSACTION_ENRICHMENT_JOB) Job transactionEnrichmentJob) {
        log.debug("Creating JobOperatorTestUtils for transactionEnrichmentJob");
        JobOperatorTestUtils utils = new JobOperatorTestUtils(jobOperator, jobRepository);
        utils.setJob(transactionEnrichmentJob);
        return utils;
    }

    @Bean
    public JobRepositoryTestUtils jobRepositoryTestUtils(JobRepository jobRepository) {
        log.debug("Creating JobRepositoryTestUtils");
        return new JobRepositoryTestUtils(jobRepository);
    }
}
