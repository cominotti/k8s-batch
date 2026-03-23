// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common.domain;

/**
 * Single source of truth for all batch job and step name constants.
 *
 * <p>These names are used in {@code JobBuilder}, {@code StepBuilder}, {@code @Qualifier}
 * annotations, REST API URLs, and Kafka partition request messages. Using string literals instead
 * of these constants risks name drift, which causes runtime {@code NoSuchStepException} errors
 * that are invisible at compile time.
 */
public final class BatchStepNames {

    // Job names — used in JobBuilder and as REST API path variables (POST /api/jobs/{jobName})
    public static final String FILE_RANGE_ETL_JOB = "fileRangeEtlJob";
    public static final String MULTI_FILE_ETL_JOB = "multiFileEtlJob";

    // Manager step names — used in StandaloneJobConfig and RemotePartitioningBaseConfig
    public static final String FILE_RANGE_MANAGER_STEP = "fileRangeManagerStep";
    public static final String MULTI_FILE_MANAGER_STEP = "multiFileManagerStep";

    // Worker step names — used in StepBuilder, @Qualifier, and BeanFactoryStepLocator resolution
    public static final String FILE_RANGE_WORKER_STEP = "fileRangeWorkerStep";
    public static final String MULTI_FILE_WORKER_STEP = "multiFileWorkerStep";

    // Transaction enrichment job — single chunk step (not partitioned), requires Kafka
    public static final String TRANSACTION_ENRICHMENT_JOB = "transactionEnrichmentJob";
    public static final String TRANSACTION_ENRICHMENT_STEP = "transactionEnrichmentStep";

    // Rules engine PoC job — CSV-to-DB with Drools/EVRete toggle, no partitioning
    public static final String RULES_ENGINE_POC_JOB = "rulesEnginePocJob";
    public static final String RULES_ENGINE_POC_STEP = "rulesEnginePocStep";

    private BatchStepNames() {
    }
}
