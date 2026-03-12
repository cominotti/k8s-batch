// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import com.cominotti.k8sbatch.it.AbstractStandaloneBatchTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates that Spring Batch metadata (job and step executions) is correctly persisted to the
 * {@code JobRepository} after a successful job run. Verifies tracking, not restart/recovery.
 */
class JobRecoveryStandaloneIT extends AbstractStandaloneBatchTest {

    @Autowired
    @Qualifier("fileRangeJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Test
    void shouldCompleteSuccessfullyOnValidInput() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-10rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldTrackJobExecutionInRepository() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-10rows.csv");

        jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Integer jobExecutionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Integer.class);
        assertThat(jobExecutionCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldPersistStepExecutionMetadata() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-10rows.csv");

        jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Integer stepCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION", Integer.class);
        assertThat(stepCount).isGreaterThanOrEqualTo(1);
    }
}
