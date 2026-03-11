// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.web;

public record JobExecutionResponse(
        long executionId,
        String jobName,
        String status,
        String exitCode,
        String exitDescription) {
}
