package com.cominotti.k8sbatch.it.batch.remote;

import com.cominotti.k8sbatch.it.AbstractBatchIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"integration-test", "remote-partitioning"})
class FileRangePartitionRemoteIT extends AbstractBatchIntegrationTest {

    @Autowired
    @Qualifier("fileRangeJobOperatorTestUtils")
    private JobOperatorTestUtils jobLauncherTestUtils;

    private JobParameters jobParams(String inputFile) {
        return new JobParametersBuilder()
                .addString("batch.file-range.input-file", inputFile)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
    }

    @Test
    void shouldCompleteEndToEnd() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-100rows.csv").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldWriteAllRowsToMysql() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-100rows.csv").getPath();

        jobLauncherTestUtils.startJob(jobParams(inputFile));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(100);
    }

    @Test
    void shouldDistributePartitionsEvenly() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-100rows.csv").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputFile));

        long workerSteps = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().contains("Worker"))
                .count();
        assertThat(workerSteps).isGreaterThan(1);
    }

    @Test
    void shouldPersistPartitionMetadata() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-100rows.csv").getPath();

        jobLauncherTestUtils.startJob(jobParams(inputFile));

        Integer stepCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION WHERE STEP_NAME LIKE '%Worker%'",
                Integer.class);
        assertThat(stepCount).isGreaterThan(0);
    }
}
