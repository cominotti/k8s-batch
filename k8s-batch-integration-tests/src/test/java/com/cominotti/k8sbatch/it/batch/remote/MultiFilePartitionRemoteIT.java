package com.cominotti.k8sbatch.it.batch.remote;

import com.cominotti.k8sbatch.it.AbstractBatchIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles({"integration-test", "remote-partitioning"})
class MultiFilePartitionRemoteIT extends AbstractBatchIntegrationTest {

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
    void shouldCompleteEndToEnd() throws Exception {
        String inputDir = getClass().getClassLoader()
                .getResource("test-data/csv/multi").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputDir));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
    }

    @Test
    void shouldAssignOneFilePerWorker() throws Exception {
        String inputDir = getClass().getClassLoader()
                .getResource("test-data/csv/multi").getPath();

        JobExecution execution = jobLauncherTestUtils.startJob(jobParams(inputDir));

        long workerSteps = execution.getStepExecutions().stream()
                .filter(s -> s.getStepName().contains("Worker"))
                .count();
        assertThat(workerSteps).isEqualTo(3);
    }

    @Test
    void shouldWriteCorrectDataPerFile() throws Exception {
        String inputDir = getClass().getClassLoader()
                .getResource("test-data/csv/multi").getPath();

        jobLauncherTestUtils.startJob(jobParams(inputDir));

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM target_records", Integer.class);
        assertThat(count).isEqualTo(120); // 30 + 40 + 50
    }
}
