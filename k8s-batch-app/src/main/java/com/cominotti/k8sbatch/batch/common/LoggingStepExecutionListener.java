package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class LoggingStepExecutionListener implements StepExecutionListener {

    private static final Logger log = LoggerFactory.getLogger(LoggingStepExecutionListener.class);

    @Override
    public void beforeStep(StepExecution stepExecution) {
        log.info("Step '{}' starting | thread={} | jobExecutionId={}",
                stepExecution.getStepName(),
                Thread.currentThread().getName(),
                stepExecution.getJobExecutionId());
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        String stepName = stepExecution.getStepName();
        String thread = Thread.currentThread().getName();
        Duration duration = computeDuration(stepExecution.getStartTime(), stepExecution.getEndTime());
        BatchStatus status = stepExecution.getStatus();

        if (status == BatchStatus.COMPLETED) {
            log.info("Step '{}' completed | read={} | written={} | filtered={} | skipped={} | duration={} | thread={}",
                    stepName,
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    stepExecution.getFilterCount(),
                    stepExecution.getReadSkipCount() + stepExecution.getWriteSkipCount() + stepExecution.getProcessSkipCount(),
                    duration, thread);
        } else if (status == BatchStatus.FAILED) {
            log.error("Step '{}' FAILED | read={} | written={} | exitDescription={} | thread={}",
                    stepName,
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    stepExecution.getExitStatus().getExitDescription(),
                    thread);
        } else {
            log.warn("Step '{}' ended with status {} | read={} | written={} | duration={} | thread={}",
                    stepName, status,
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    duration, thread);
        }

        return null;
    }

    private Duration computeDuration(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }
}
