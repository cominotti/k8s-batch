package com.cominotti.k8sbatch.it.batch.standalone;

import com.cominotti.k8sbatch.it.AbstractStandaloneBatchTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import static org.assertj.core.api.Assertions.assertThat;

class JobRecoveryStandaloneIT extends AbstractStandaloneBatchTest {

    @Autowired
    @Qualifier("fileRangeJobOperatorTestUtils")
    private JobOperatorTestUtils jobLauncherTestUtils;

    @Test
    void shouldCompleteSuccessfullyOnValidInput() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-10rows.csv").getPath();

        JobParameters params = new JobParametersBuilder()
                .addString("batch.file-range.input-file", inputFile)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        JobExecution execution = jobLauncherTestUtils.startJob(params);
        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldTrackJobExecutionInRepository() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-10rows.csv").getPath();

        JobParameters params = new JobParametersBuilder()
                .addString("batch.file-range.input-file", inputFile)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        jobLauncherTestUtils.startJob(params);

        Integer jobExecutionCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_JOB_EXECUTION", Integer.class);
        assertThat(jobExecutionCount).isGreaterThanOrEqualTo(1);
    }

    @Test
    void shouldPersistStepExecutionMetadata() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-10rows.csv").getPath();

        JobParameters params = new JobParametersBuilder()
                .addString("batch.file-range.input-file", inputFile)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();

        jobLauncherTestUtils.startJob(params);

        Integer stepCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM BATCH_STEP_EXECUTION", Integer.class);
        assertThat(stepCount).isGreaterThanOrEqualTo(1);
    }
}
