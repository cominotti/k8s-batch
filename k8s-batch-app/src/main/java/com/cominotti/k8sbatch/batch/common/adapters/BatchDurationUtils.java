// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common.adapters;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Null-safe duration calculation for batch listener logging. Spring Batch's
 * {@code JobExecution.getEndTime()} returns {@code null} if a job crashes before completion,
 * which would cause {@code Duration.between()} to throw a {@code NullPointerException}.
 */
final class BatchDurationUtils {

    private BatchDurationUtils() {
    }

    static Duration between(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }
}
