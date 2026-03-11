// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.batch;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.client.BatchAppClient.JobResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the multi-file ETL job.
 * Processes multiple CSV files from a directory mounted via ConfigMap.
 * Expected: file-a.csv (30 rows) + file-b.csv (40 rows) + file-c.csv (50 rows) = 120 rows.
 */
class MultiFileJobE2E extends AbstractE2ETest {

    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    @Override
    protected boolean requiresKafka() {
        return true;
    }

    @Test
    void shouldProcessMultipleFilesAndWriteAllRecords() throws Exception {
        // Multi-file data is mounted at /data/test/multi/ via ConfigMap
        // Note: ConfigMap mounts individual files, so the directory contains the multi files
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "multiFileEtlJob",
                Map.of("batch.multi-file.input-directory", "/data/test/multi"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");

        // file-a.csv: 30 rows, file-b.csv: 40 rows, file-c.csv: 50 rows = 120
        int recordCount = mysqlVerifier.countTargetRecords();
        assertThat(recordCount).isEqualTo(120);
    }

    @Test
    void shouldCreateOneWorkerStepPerFile() throws Exception {
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "multiFileEtlJob",
                Map.of("batch.multi-file.input-directory", "/data/test/multi"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");

        // Should have 3 worker step executions (one per file)
        int workerSteps = mysqlVerifier.countStepExecutions("multiFileWorkerStep%");
        assertThat(workerSteps).isEqualTo(3);
    }
}
