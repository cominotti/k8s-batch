package com.cominotti.k8sbatch.batch.common;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "batch.partition")
public record BatchPartitionProperties(int gridSize, int chunkSize) {

    public BatchPartitionProperties {
        if (gridSize <= 0) {
            gridSize = 4;
        }
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
    }
}
