// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common.adapters.observingexecution.logging;

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

    /**
     * Computes the duration between two timestamps, returning {@link Duration#ZERO} if either
     * argument is {@code null}. This prevents {@code Duration.between()} from throwing a
     * {@code NullPointerException} when Spring Batch has not yet populated end times (e.g., a
     * job or step that crashed before completion).
     *
     * @param start execution start time (may be {@code null})
     * @param end   execution end time (may be {@code null})
     * @return duration between start and end, or {@link Duration#ZERO} if either is {@code null}
     */
    static Duration between(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null) {
            return Duration.ZERO;
        }
        return Duration.between(start, end);
    }
}
