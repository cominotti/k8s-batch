// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.batch;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.E2EProfile;
import com.cominotti.k8sbatch.e2e.client.BatchAppClient.JobResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the file-range ETL job deployed via Helm into a K3s cluster with Kafka-based
 * remote partitioning. The job splits a single CSV file by line ranges, distributes partitions
 * to workers through Kafka, and writes records to MySQL. Validates both small (10-row) and
 * large (100-row, multi-partition) inputs to ensure correct data throughput and worker scaling.
 */
@E2EProfile("e2e-remote.yaml")
class FileRangeJobE2E extends AbstractE2ETest {

    /** {@inheritDoc} Deploys the remote-partitioning stack with Kafka for partition distribution. */
    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    /** {@inheritDoc} Kafka is required for remote partitioning of CSV file ranges. */
    @Override
    protected boolean requiresKafka() {
        return true;
    }

    /**
     * Verifies the basic happy path: a 10-row CSV processed through the file-range job
     * produces exactly 10 records in MySQL. This is a small input that fits in a single
     * partition, validating the end-to-end data path without multi-partition complexity.
     */
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

    /**
     * Verifies that a 100-row CSV triggers multiple worker step executions through Kafka
     * remote partitioning. The partitioner splits the file into line ranges, each assigned
     * to a separate worker step. Asserts that all 100 records are written and that at least
     * 2 worker steps were created (confirming partitioning actually occurred).
     */
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
