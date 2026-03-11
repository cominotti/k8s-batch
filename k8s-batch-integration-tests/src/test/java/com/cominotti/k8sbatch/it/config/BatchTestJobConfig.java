package com.cominotti.k8sbatch.it.config;

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

@TestConfiguration(proxyBeanMethods = false)
public class BatchTestJobConfig {

    private static final Logger log = LoggerFactory.getLogger(BatchTestJobConfig.class);

    @Bean
    public JobOperatorTestUtils fileRangeJobOperatorTestUtils(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier("fileRangeEtlJob") Job fileRangeEtlJob) {
        log.debug("Creating JobOperatorTestUtils for fileRangeEtlJob");
        JobOperatorTestUtils utils = new JobOperatorTestUtils(jobOperator, jobRepository);
        utils.setJob(fileRangeEtlJob);
        return utils;
    }

    @Bean
    public JobOperatorTestUtils multiFileJobOperatorTestUtils(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier("multiFileEtlJob") Job multiFileEtlJob) {
        log.debug("Creating JobOperatorTestUtils for multiFileEtlJob");
        JobOperatorTestUtils utils = new JobOperatorTestUtils(jobOperator, jobRepository);
        utils.setJob(multiFileEtlJob);
        return utils;
    }

    @Bean
    public JobRepositoryTestUtils jobRepositoryTestUtils(JobRepository jobRepository) {
        log.debug("Creating JobRepositoryTestUtils");
        return new JobRepositoryTestUtils(jobRepository);
    }
}
