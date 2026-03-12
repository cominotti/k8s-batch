// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import com.cominotti.k8sbatch.it.AbstractStandaloneBatchTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the multi-file ETL job under standalone in-process partitioning (no Kafka). */
class MultiFilePartitionStandaloneIT extends AbstractStandaloneBatchTest {

    @Autowired
    @Qualifier("multiFileJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Test
    void shouldCompleteJobSuccessfully() throws Exception {
        String inputDir = testResourcePath("test-data/csv/multi");

        JobExecution execution = jobOperatorTestUtils.startJob(multiFileJobParams(inputDir));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(120); // 30 + 40 + 50
    }

    @Test
    void shouldCreateOnePartitionPerFile() throws Exception {
        String inputDir = testResourcePath("test-data/csv/multi");

        JobExecution execution = jobOperatorTestUtils.startJob(multiFileJobParams(inputDir));

        long workerSteps = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().startsWith("multiFileWorkerStep"))
                .count();
        assertThat(workerSteps).isEqualTo(3); // 3 CSV files
    }

    @Test
    void shouldHandleSingleFile() throws Exception {
        // Use a directory with only the single file directory structure
        String inputDir = testResourcePath("test-data/csv/single");

        JobExecution execution = jobOperatorTestUtils.startJob(multiFileJobParams(inputDir));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldTrackReadAndWriteCounts() throws Exception {
        String inputDir = testResourcePath("test-data/csv/multi");

        JobExecution execution = jobOperatorTestUtils.startJob(multiFileJobParams(inputDir));

        long totalWriteCount = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().startsWith("multiFileWorkerStep"))
                .mapToLong(StepExecution::getWriteCount)
                .sum();
        assertThat(totalWriteCount).isEqualTo(120);
    }
}
