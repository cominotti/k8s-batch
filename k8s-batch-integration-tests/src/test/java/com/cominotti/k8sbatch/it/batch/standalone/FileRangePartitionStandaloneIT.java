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

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

/** Validates the file-range ETL job under standalone in-process partitioning (no Kafka). */
class FileRangePartitionStandaloneIT extends AbstractStandaloneBatchTest {

    @Autowired
    @Qualifier("fileRangeJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Test
    void shouldCompleteJobSuccessfully() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(100);
    }

    @Test
    void shouldPartitionByLineRanges() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        long workerSteps = stepExecutions.stream()
                .filter(s -> s.getStepName().startsWith("fileRangeWorkerStep"))
                .count();
        assertThat(workerSteps).isGreaterThan(1);
    }

    @Test
    void shouldHandleSmallFile() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-10rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(10);
    }

    @Test
    void shouldSkipMalformedRows() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-with-errors.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isLessThan(50); // some rows filtered by processor
    }

    @Test
    void shouldRecordStepExecutionMetadata() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        long totalReadCount = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().startsWith("fileRangeWorkerStep"))
                .mapToLong(StepExecution::getReadCount)
                .sum();
        assertThat(totalReadCount).isEqualTo(100);
    }
}
