// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.web.config;

import org.springframework.batch.core.launch.support.JobOperatorFactoryBean;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

/**
 * Provides an async {@link org.springframework.batch.core.launch.JobOperator} for the REST API.
 *
 * <p>The default auto-configured {@code JobOperator} is synchronous — {@code start()} blocks until
 * the job completes. This config creates a second operator backed by a
 * {@link SimpleAsyncTaskExecutor} so that
 * {@link com.cominotti.k8sbatch.web.adapters.launchingjobs.rest.JobController}'s POST returns HTTP 202 immediately
 * while the job runs in a background thread.
 */
@Configuration(proxyBeanMethods = false)
class AsyncJobOperatorConfig {

    /**
     * Async {@link org.springframework.batch.core.launch.JobOperator} for REST job launches.
     *
     * <p>Spring manages the {@link JobOperatorFactoryBean} lifecycle — calling
     * {@code afterPropertiesSet()} (which discovers {@code Job} beans via the
     * {@code ApplicationContext}) and {@code getObject()} automatically.
     *
     * @param jobRepository repository used to persist job metadata
     * @return factory that produces an async {@code JobOperator}
     */
    @Bean("asyncJobOperator")
    JobOperatorFactoryBean asyncJobOperator(JobRepository jobRepository) {
        JobOperatorFactoryBean factory = new JobOperatorFactoryBean();
        factory.setJobRepository(jobRepository);
        factory.setTaskExecutor(new SimpleAsyncTaskExecutor("job-api-"));
        return factory;
    }
}
