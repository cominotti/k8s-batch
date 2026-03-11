// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.time.Duration;

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
        Duration duration = BatchDurationUtils.between(stepExecution.getStartTime(), stepExecution.getEndTime());
        BatchStatus status = stepExecution.getStatus();

        switch (status) {
            case COMPLETED -> log.info("Step '{}' completed | read={} | written={} | filtered={} | skipped={} | duration={} | thread={}",
                    stepName,
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    stepExecution.getFilterCount(),
                    stepExecution.getReadSkipCount() + stepExecution.getWriteSkipCount() + stepExecution.getProcessSkipCount(),
                    duration, thread);
            case FAILED -> log.error("Step '{}' FAILED | read={} | written={} | exitDescription={} | thread={}",
                    stepName,
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    stepExecution.getExitStatus().getExitDescription(),
                    thread);
            default -> log.warn("Step '{}' ended with status {} | read={} | written={} | duration={} | thread={}",
                    stepName, status,
                    stepExecution.getReadCount(),
                    stepExecution.getWriteCount(),
                    duration, thread);
        }

        return null;
    }
}
