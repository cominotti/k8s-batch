// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.remote;

import com.cominotti.k8sbatch.it.AbstractBatchIntegrationTest;
import com.cominotti.k8sbatch.it.config.SharedContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@Import(SharedContainersConfig.class)
@ActiveProfiles({"integration-test", "remote-partitioning"})
class MultiFilePartitionRemoteIT extends AbstractBatchIntegrationTest {

    @Autowired
    @Qualifier("multiFileJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Test
    void shouldCompleteEndToEnd() throws Exception {
        String inputDir = testResourcePath("test-data/csv/multi");

        JobExecution execution = jobOperatorTestUtils.startJob(multiFileJobParams(inputDir));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldAssignOneFilePerWorker() throws Exception {
        String inputDir = testResourcePath("test-data/csv/multi");

        JobExecution execution = jobOperatorTestUtils.startJob(multiFileJobParams(inputDir));

        long workerSteps = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().contains("Worker"))
                .count();
        assertThat(workerSteps).isEqualTo(3);
    }

    @Test
    void shouldWriteCorrectDataPerFile() throws Exception {
        String inputDir = testResourcePath("test-data/csv/multi");

        jobOperatorTestUtils.startJob(multiFileJobParams(inputDir));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(120); // 30 + 40 + 50
    }
}
