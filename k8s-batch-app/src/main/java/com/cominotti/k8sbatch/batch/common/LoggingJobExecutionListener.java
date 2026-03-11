package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.listener.JobExecutionListener;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class LoggingJobExecutionListener implements JobExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingJobExecutionListener.class);

    @Override
    public void beforeJob(JobExecution jobExecution) {
        log.info("Job '{}' starting | jobExecutionId={} | parameters={}",
                jobExecution.getJobInstance().getJobName(),
                jobExecution.getId(),
                jobExecution.getJobParameters());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        String jobName = jobExecution.getJobInstance().getJobName();
        Long executionId = jobExecution.getId();
        Duration duration = computeDuration(jobExecution.getStartTime(), jobExecution.getEndTime());
        BatchStatus status = jobExecution.getStatus();

        if (status == BatchStatus.COMPLETED) {
            log.info("Job '{}' completed | jobExecutionId={} | duration={} | steps={}",
                    jobName, executionId, duration, jobExecution.getStepExecutions().size());
        } else if (status == BatchStatus.FAILED) {
            log.error("Job '{}' FAILED | jobExecutionId={} | duration={} | exitDescription={}",
                    jobName, executionId, duration,
                    jobExecution.getExitStatus().getExitDescription());
        } else {
            log.warn("Job '{}' ended with status {} | jobExecutionId={} | duration={}",
                    jobName, status, executionId, duration);
        }
    }

    private Duration computeDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }
}
