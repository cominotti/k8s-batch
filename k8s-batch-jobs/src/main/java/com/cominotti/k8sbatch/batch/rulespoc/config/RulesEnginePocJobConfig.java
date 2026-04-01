// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.config;

import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingJobExecutionListener;
import com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging.LoggingStepExecutionListener;
import com.cominotti.k8sbatch.batch.common.config.BatchFileProperties;
import com.cominotti.k8sbatch.batch.common.domain.BatchStepNames;
import com.cominotti.k8sbatch.batch.rulespoc.adapters.persistingresults.jdbc.EnrichedFinancialTransactionWriter;
import com.cominotti.k8sbatch.batch.rulespoc.adapters.readingcsv.file.FinancialTransactionReaderFactory;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter;
import org.springframework.batch.infrastructure.item.file.FlatFileItemReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Defines the {@code rulesEnginePocJob} — a non-partitioned CSV-to-DB batch job that applies
 * financial business rules using the active rules engine adapter, controlled by the
 * {@code batch.rules.engine} property.
 *
 * <p>The job reads financial transactions from a CSV file, delegates enrichment to the active
 * {@link TransactionRulesEvaluator} implementation, and writes enriched results to MySQL. This
 * is a proof-of-concept for evaluating rules engine alternatives — existing jobs are not modified.
 */
@Configuration
@EnableConfigurationProperties(RulesEngineProperties.class)
public class RulesEnginePocJobConfig {

    private static final Logger log = LoggerFactory.getLogger(RulesEnginePocJobConfig.class);

    private final RulesEngineProperties rulesProperties;
    private final BatchFileProperties fileProperties;
    private final LoggingJobExecutionListener jobExecutionListener;
    private final LoggingStepExecutionListener stepExecutionListener;

    /**
     * Injects shared batch infrastructure beans and rules engine configuration.
     *
     * @param rulesProperties    rules engine toggle and chunk size
     * @param fileProperties     allowed base directory for input file validation
     * @param jobExecutionListener  logs job start/end events
     * @param stepExecutionListener logs step start/end events
     */
    public RulesEnginePocJobConfig(RulesEngineProperties rulesProperties,
                                   BatchFileProperties fileProperties,
                                   LoggingJobExecutionListener jobExecutionListener,
                                   LoggingStepExecutionListener stepExecutionListener) {
        this.rulesProperties = rulesProperties;
        this.fileProperties = fileProperties;
        this.jobExecutionListener = jobExecutionListener;
        this.stepExecutionListener = stepExecutionListener;
        log.info("RulesEnginePocJobConfig initialized | engine={} | chunkSize={}",
                rulesProperties.engine(), rulesProperties.chunkSize());
    }

    /**
     * Assembles the rules engine PoC job with a single chunk step.
     *
     * @param jobRepository        persists job metadata
     * @param rulesEnginePocStep   the enrichment step
     * @return the fully configured {@code rulesEnginePocJob}
     */
    @Bean
    public Job rulesEnginePocJob(JobRepository jobRepository, Step rulesEnginePocStep) {
        return new JobBuilder(BatchStepNames.RULES_ENGINE_POC_JOB, jobRepository)
                .listener(jobExecutionListener)
                .start(rulesEnginePocStep)
                .build();
    }

    /**
     * Creates a {@code @StepScope} CSV reader for financial transactions. The input file path
     * is resolved from job parameters.
     *
     * @param inputFile CSV file path from the {@code batch.rules.input-file} job parameter
     * @return configured reader for {@link FinancialTransaction} records
     */
    @Bean
    @StepScope
    public FlatFileItemReader<FinancialTransaction> rulesEnginePocReader(
            @Value("#{jobParameters['batch.rules.input-file']}") String inputFile) {
        String safePath = fileProperties.requireWithinAllowedBase(inputFile);
        return FinancialTransactionReaderFactory.create(new FileSystemResource(safePath));
    }

    /**
     * Creates a {@code @StepScope} processor that delegates to the active rules evaluator.
     *
     * @param rulesEvaluator the active {@link TransactionRulesEvaluator} implementation
     * @return processor wrapping the rules evaluator
     */
    @Bean
    @StepScope
    public RulesEngineProcessor rulesEnginePocProcessor(TransactionRulesEvaluator rulesEvaluator) {
        return new RulesEngineProcessor(rulesEvaluator);
    }

    /**
     * Creates a {@code @StepScope} JDBC writer for enriched financial transactions.
     *
     * @param dataSource MySQL data source
     * @return idempotent writer using {@code ON DUPLICATE KEY UPDATE}
     */
    @Bean
    @StepScope
    public JdbcBatchItemWriter<EnrichedFinancialTransaction> rulesEnginePocWriter(
            DataSource dataSource) {
        return EnrichedFinancialTransactionWriter.create(dataSource);
    }

    /**
     * Single chunk step: reads CSV, applies rules, writes enriched results to MySQL.
     *
     * @param jobRepository             persists step metadata
     * @param transactionManager        wraps each chunk in a database transaction
     * @param rulesEnginePocReader      CSV reader
     * @param rulesEnginePocProcessor   rules engine processor
     * @param rulesEnginePocWriter      JDBC writer
     * @return the configured step
     */
    @Bean
    public Step rulesEnginePocStep(
            JobRepository jobRepository,
            PlatformTransactionManager transactionManager,
            FlatFileItemReader<FinancialTransaction> rulesEnginePocReader,
            RulesEngineProcessor rulesEnginePocProcessor,
            JdbcBatchItemWriter<EnrichedFinancialTransaction> rulesEnginePocWriter) {
        return new StepBuilder(BatchStepNames.RULES_ENGINE_POC_STEP, jobRepository)
                .<FinancialTransaction, EnrichedFinancialTransaction>chunk(rulesProperties.chunkSize())
                .transactionManager(transactionManager)
                .reader(rulesEnginePocReader)
                .processor(rulesEnginePocProcessor)
                .writer(rulesEnginePocWriter)
                .listener(stepExecutionListener)
                .build();
    }
}
