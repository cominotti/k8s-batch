package com.cominotti.k8sbatch.it.batch.remote;

import com.cominotti.k8sbatch.it.AbstractBatchIntegrationTest;
import com.cominotti.k8sbatch.it.config.SharedContainersConfig;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collection;

import static org.assertj.core.api.Assertions.assertThat;

@Import(SharedContainersConfig.class)
@ActiveProfiles({"integration-test", "remote-partitioning"})
class PartitionDistributionIT extends AbstractBatchIntegrationTest {

    @Autowired
    @Qualifier("fileRangeJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Test
    void shouldCreateMultiplePartitions() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Collection<StepExecution> steps = execution.getStepExecutions();
        long managerSteps = steps.stream()
                .filter(s -> s.getStepName().contains("Manager"))
                .count();
        long workerSteps = steps.stream()
                .filter(s -> s.getStepName().contains("Worker"))
                .count();

        assertThat(managerSteps).isEqualTo(1);
        assertThat(workerSteps).isGreaterThanOrEqualTo(2);
    }

    @Test
    void shouldCompleteAllPartitions() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Collection<StepExecution> freshSteps = jobRepository.getJobExecution(execution.getId()).getStepExecutions();
        boolean allWorkerStepsCompleted = freshSteps.stream()
                .filter(s -> s.getStepName().contains("Worker"))
                .allMatch(s -> s.getStatus() == BatchStatus.COMPLETED);

        assertThat(allWorkerStepsCompleted).isTrue();
    }

    @Test
    void shouldRecordReadWriteCountsPerPartition() throws Exception {
        String inputFile = testResourcePath("test-data/csv/single/sample-100rows.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(fileRangeJobParams(inputFile));

        Collection<StepExecution> freshSteps = jobRepository.getJobExecution(execution.getId()).getStepExecutions();
        long totalReadCount = freshSteps.stream()
                .filter(s -> s.getStepName().contains("Worker"))
                .mapToLong(StepExecution::getReadCount)
                .sum();

        assertThat(totalReadCount).isEqualTo(100);
    }
}
