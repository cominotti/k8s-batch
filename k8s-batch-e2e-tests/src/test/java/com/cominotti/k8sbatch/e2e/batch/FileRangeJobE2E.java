// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.batch;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.client.BatchAppClient.JobResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the file-range ETL job.
 * Triggers the job via REST API with a CSV mounted into the pod via ConfigMap,
 * then verifies data was written to MySQL.
 */
class FileRangeJobE2E extends AbstractE2ETest {

    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    @Override
    protected boolean requiresKafka() {
        return true;
    }

    @Test
    void shouldProcessFileRangeJobWith10Rows() throws Exception {
        // The CSV file is mounted at /data/test/sample-10rows.csv via ConfigMap
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "fileRangeEtlJob",
                Map.of("batch.file-range.input-file", "/data/test/sample-10rows.csv"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.exitCode()).isEqualTo("COMPLETED");

        // Verify data was written to MySQL
        int recordCount = mysqlVerifier.countTargetRecords();
        assertThat(recordCount).isEqualTo(10);
    }

    @Test
    void shouldCreateMultipleWorkerSteps() throws Exception {
        // Use 100-row file to ensure multiple partitions
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "fileRangeEtlJob",
                Map.of("batch.file-range.input-file", "/data/test/sample-100rows.csv"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");

        // Verify all 100 records written
        int recordCount = mysqlVerifier.countTargetRecords();
        assertThat(recordCount).isEqualTo(100);

        // Verify worker steps were created (pattern: fileRangeWorkerStep:partition%)
        int workerSteps = mysqlVerifier.countStepExecutionsForJob(
                result.executionId(), "fileRangeWorkerStep%");
        assertThat(workerSteps).as("Should have multiple worker step executions").isGreaterThan(1);
    }
}
