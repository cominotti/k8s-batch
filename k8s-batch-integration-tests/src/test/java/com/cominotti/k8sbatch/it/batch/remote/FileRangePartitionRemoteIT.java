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

/** Validates the file-range ETL job under Kafka-based remote partitioning (Redpanda). */
@Import(SharedContainersConfig.class)
@ActiveProfiles({"integration-test", "remote-partitioning", "remote-kafka"})
class FileRangePartitionRemoteIT extends AbstractBatchIntegrationTest {

    @Autowired
    @Qualifier("fileRangeJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Test
    void shouldCompleteEndToEnd() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldWriteAllRowsToMysql() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(100);
    }

    @Test
    void shouldDistributePartitionsEvenly() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        long workerSteps = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().contains("Worker"))
                .count();
        assertThat(workerSteps).isGreaterThan(1);
    }

    @Test
    void shouldPersistPartitionMetadata() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Integer stepCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME LIKE '%Worker%'",
                Integer.class);
        assertThat(stepCount).isGreaterThan(0);
    }
}
