package com.cominotti.k8sbatch.it.config;

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

    @Bean
    public JobOperatorTestUtils fileRangeJobTestUtils(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier("fileRangeEtlJob") Job fileRangeEtlJob) {
        JobOperatorTestUtils utils = new JobOperatorTestUtils(jobOperator, jobRepository);
        utils.setJob(fileRangeEtlJob);
        return utils;
    }

    @Bean
    public JobOperatorTestUtils multiFileJobTestUtils(
            JobOperator jobOperator,
            JobRepository jobRepository,
            @Qualifier("multiFileEtlJob") Job multiFileEtlJob) {
        JobOperatorTestUtils utils = new JobOperatorTestUtils(jobOperator, jobRepository);
        utils.setJob(multiFileEtlJob);
        return utils;
    }

    @Bean
    public JobRepositoryTestUtils jobRepositoryTestUtils(JobRepository jobRepository) {
        return new JobRepositoryTestUtils(jobRepository);
    }
}
