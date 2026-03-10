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

import static org.assertj.core.api.Assertions.assertThat;

class MultiFilePartitionStandaloneIT extends AbstractStandaloneBatchTest {

    @Autowired
    @Qualifier("multiFileJobOperatorTestUtils")
    private JobOperatorTestUtils jobLauncherTestUtils;

    private JobParameters jobParams(String inputDirectory) {
        return new JobParametersBuilder()
                .addString("batch.multi-file.input-directory", inputDirectory)
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
    }

    @Test
    void shouldCompleteJobSuccessfully() throws Exception {
        String inputDir = getClass().getClassLoader()
                .getResource("test-data/csv/multi").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputDir));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(120); // 30 + 40 + 50
    }

    @Test
    void shouldCreateOnePartitionPerFile() throws Exception {
        String inputDir = getClass().getClassLoader()
                .getResource("test-data/csv/multi").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputDir));

        long workerSteps = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().startsWith("multiFileWorkerStep"))
                .count();
        assertThat(workerSteps).isEqualTo(3); // 3 CSV files
    }

    @Test
    void shouldHandleSingleFile() throws Exception {
        // Use a directory with only the single file directory structure
        String inputDir = getClass().getClassLoader()
                .getResource("test-data/csv/single").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputDir));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldTrackReadAndWriteCounts() throws Exception {
        String inputDir = getClass().getClassLoader()
                .getResource("test-data/csv/multi").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputDir));

        long totalWriteCount = execution.getStepExecutions().stream()
                .mapToLong(StepExecution::getWriteCount)
                .sum();
        assertThat(totalWriteCount).isEqualTo(120);
    }
}
