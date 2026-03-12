// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.web;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobLauncher;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.task.SimpleAsyncTaskExecutor;

import java.util.Map;

/**
 * REST API for launching and polling batch jobs asynchronously.
 *
 * <p>{@code POST /api/jobs/{jobName}} launches a job in a background thread and returns HTTP 202
 * immediately. {@code GET /api/jobs/{jobName}/executions/{executionId}} polls execution status.
 * Job names must exactly match Spring bean names defined in {@link com.cominotti.k8sbatch.batch.common.BatchStepNames
 * BatchStepNames}.
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private static final Logger log = LoggerFactory.getLogger(JobController.class);

    private final JobLauncher asyncJobLauncher;
    private final JobRepository jobRepository;
    // Spring auto-wires all Job beans into a map keyed by bean name
    private final Map<String, Job> jobRegistry;

    public JobController(JobRepository jobRepository, Map<String, Job> jobRegistry) {
        this.jobRepository = jobRepository;
        this.jobRegistry = jobRegistry;

        // Create an async launcher so POST returns immediately
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("job-api-"));
        this.asyncJobLauncher = launcher;

        log.info("JobController initialized | registeredJobs={}", jobRegistry.keySet());
    }

    @PostMapping("/{jobName}")
    public ResponseEntity<JobExecutionResponse> launchJob(
            @PathVariable String jobName,
            @RequestBody(required = false) Map<String, String> parameters) {
        log.info("Received job launch request | jobName={}", jobName);
        Job job = jobRegistry.get(jobName);
        if (job == null) {
            log.warn("Unknown job requested | jobName={} | available={}", jobName, jobRegistry.keySet());
            return ResponseEntity.badRequest()
                    .body(new JobExecutionResponse(-1, jobName, "FAILED", "FAILED",
                            "Unknown job: " + jobName + ". Available: " + jobRegistry.keySet()));
        }

        try {
            JobParametersBuilder builder = new JobParametersBuilder();
            if (parameters != null) {
                parameters.forEach(builder::addString);
            }
            // Timestamp makes each launch unique — Spring Batch rejects duplicate JobParameters
            builder.addLong("timestamp", System.currentTimeMillis());
            JobParameters jobParameters = builder.toJobParameters();

            // Async launch: creates the JobExecution, starts in background thread, returns immediately
            JobExecution execution = asyncJobLauncher.run(job, jobParameters);
            long executionId = execution.getId();

            JobExecutionResponse response = toResponse(executionId, jobName, execution);
            log.info("Job launch accepted | jobName={} | executionId={} | status={}",
                    jobName, executionId, response.status());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
        } catch (Exception e) {
            log.error("Failed to launch job | jobName={}", jobName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(new JobExecutionResponse(-1, jobName, "FAILED", "FAILED", e.getMessage()));
        }
    }

    @GetMapping("/{jobName}/executions/{executionId}")
    public ResponseEntity<JobExecutionResponse> getExecution(
            @PathVariable String jobName,
            @PathVariable long executionId) {
        JobExecution execution = jobRepository.getJobExecution(executionId);
        if (execution == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toResponse(executionId, jobName, execution));
    }

    private static JobExecutionResponse toResponse(long executionId, String jobName, JobExecution execution) {
        String exitCode = execution.getExitStatus() != null
                ? execution.getExitStatus().getExitCode() : "";
        String exitDescription = execution.getExitStatus() != null
                ? execution.getExitStatus().getExitDescription() : "";
        return new JobExecutionResponse(executionId, jobName, execution.getStatus().name(),
                exitCode, exitDescription);
    }
}
