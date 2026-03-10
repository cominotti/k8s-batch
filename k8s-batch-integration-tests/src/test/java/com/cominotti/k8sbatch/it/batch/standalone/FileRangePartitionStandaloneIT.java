package com.cominotti.k8sbatch.it.batch.standalone;

import com.cominotti.k8sbatch.it.AbstractStandaloneBatchTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

class FileRangePartitionStandaloneIT extends AbstractStandaloneBatchTest {

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
    void shouldCompleteJobSuccessfully() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-100rows.csv").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(100);
    }

    @Test
    void shouldPartitionByLineRanges() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-100rows.csv").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputFile));

        Collection<StepExecution> stepExecutions = execution.getStepExecutions();
        long workerSteps = stepExecutions.stream()
                .filter(s -> s.getStepName().startsWith("fileRangeWorkerStep"))
                .count();
        assertThat(workerSteps).isGreaterThan(1);
    }

    @Test
    void shouldHandleSmallFile() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-10rows.csv").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(10);
    }

    @Test
    void shouldSkipMalformedRows() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-with-errors.csv").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isLessThan(50); // some rows filtered by processor
    }

    @Test
    void shouldRecordStepExecutionMetadata() throws Exception {
        String inputFile = getClass().getClassLoader()
                .getResource("test-data/csv/single/sample-100rows.csv").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputFile));

        long totalReadCount = execution.getStepExecutions().stream()
                .mapToLong(StepExecution::getReadCount)
                .sum();
        assertThat(totalReadCount).isEqualTo(100);
    }
}
