// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.batch;

import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.client.BatchAppClient.JobResponse;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test that validates the partition distribution metadata stored in Spring Batch's
 * {@code BATCH_STEP_EXECUTION} table. Deploys the full Kafka-based stack into K3s, runs
 * a 100-row file-range job, and queries MySQL to verify that the manager step created
 * multiple worker partitions whose total read counts sum to exactly 100 rows.
 */
class PartitionDistributionE2E extends AbstractE2ETest {

    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    @Override
    protected boolean requiresKafka() {
        return true;
    }

    @Test
    void shouldDistributeWorkAcrossPartitions() throws Exception {
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "fileRangeEtlJob",
                Map.of("batch.file-range.input-file", "/data/test/sample-100rows.csv"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");
        long executionId = result.executionId();

        // Query step executions
        List<Map<String, Object>> steps = mysqlVerifier.queryStepExecutions(executionId);

        // Should have 1 manager step + >= 2 worker steps
        long managerSteps = steps.stream()
                .filter(s -> "fileRangeManagerStep".equals(s.get("STEP_NAME")))
                .count();
        assertThat(managerSteps).as("Should have exactly 1 manager step").isEqualTo(1);

        long workerSteps = steps.stream()
                .filter(s -> ((String) s.get("STEP_NAME")).startsWith("fileRangeWorkerStep"))
                .count();
        assertThat(workerSteps).as("Should have >= 2 worker step executions").isGreaterThanOrEqualTo(2);

        // All steps should be COMPLETED
        for (Map<String, Object> step : steps) {
            assertThat(step.get("STATUS"))
                    .as("Step %s should be COMPLETED", step.get("STEP_NAME"))
                    .isEqualTo("COMPLETED");
        }

        // Total read count across workers should equal 100
        int totalReads = mysqlVerifier.totalReadCount(executionId, "fileRangeWorkerStep%");
        assertThat(totalReads).as("Total reads across all worker partitions").isEqualTo(100);
    }
}
