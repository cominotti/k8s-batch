// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Partition configuration bound to the {@code batch.partition.*} properties.
 *
 * @param gridSize  number of partitions the {@code Partitioner} creates (default: 4)
 * @param chunkSize items per transaction in each worker step's chunk processing (default: 100)
 * @param timeoutMs remote partitioning manager polling timeout in milliseconds (default: 60000).
 *                  Only applies to remote mode — standalone {@code TaskExecutorPartitionHandler}
 *                  has no timeout API
 */
@ConfigurationProperties(prefix = "batch.partition")
public record BatchPartitionProperties(int gridSize, int chunkSize, long timeoutMs) {

    // Compact constructor applies defaults because records cannot use field initializers
    public BatchPartitionProperties {
        if (gridSize <= 0) {
            gridSize = 4;
        }
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
        if (timeoutMs <= 0) {
            timeoutMs = 60_000;
        }
    }
}
