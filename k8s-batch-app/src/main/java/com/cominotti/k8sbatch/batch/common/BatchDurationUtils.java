// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import java.time.Duration;
import java.time.LocalDateTime;

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
