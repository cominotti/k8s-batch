// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.web.dto;

/**
 * DTO returned by the job REST API. {@code status} is the {@code BatchStatus} name (STARTING,
 * STARTED, COMPLETED, FAILED). {@code exitCode} and {@code exitDescription} are only meaningful
 * after the job completes — poll until {@code status} is a terminal state.
 */
public record JobExecutionResponse(
        long executionId,
        String jobName,
        String status,
        String exitCode,
        String exitDescription) {
}
