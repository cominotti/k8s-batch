// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.filerange;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.core.io.Resource;

import org.springframework.core.io.FileSystemResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * Splits a single CSV file into line-range partitions for parallel processing.
 *
 * <p>Implements the Spring Batch {@link Partitioner} contract: {@link #partition(int)} returns a
 * map of {@link ExecutionContext} objects, one per partition. Each context carries the keys
 * {@code startLine}, {@code endLine}, and {@code resourcePath} — these travel over Kafka (remote
 * mode) or are passed in-memory (standalone mode), and are injected into worker step beans via
 * {@code @Value("#{stepExecutionContext['...']}}")} in {@code FileRangeJobConfig}.
 */
public class FileRangePartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(FileRangePartitioner.class);

    private final Resource resource;

    /**
     * Creates a partitioner for the given CSV file resource.
     *
     * @param resource the CSV file to split into line-range partitions
     */
    public FileRangePartitioner(Resource resource) {
        this.resource = resource;
    }

    /**
     * Creates partitions by dividing the file's data lines into ranges.
     *
     * @param gridSize hint for desired partition count — actual count may be smaller if the file
     *                 has fewer data lines than {@code gridSize}
     */
    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int totalLines = countLines();
        int linesPerPartition = Math.max(1, (int) Math.ceil((double) totalLines / gridSize));

        log.info("FileRangePartitioner: totalLines={} | gridSize={} | linesPerPartition={} | resource={}",
                totalLines, gridSize, linesPerPartition, resource.getDescription());

        Map<String, ExecutionContext> partitions = new HashMap<>();
        int start = 0;

        for (int i = 0; i < gridSize && start < totalLines; i++) {
            ExecutionContext context = new ExecutionContext();
            int end = Math.min(start + linesPerPartition, totalLines);

            // These key names must exactly match the @Value SpEL expressions in FileRangeJobConfig
            context.putInt("startLine", start);
            context.putInt("endLine", end);
            // Cast to FileSystemResource to get the filesystem path — the Resource interface
            // doesn't expose a raw path
            context.putString("resourcePath", ((FileSystemResource) resource).getPath());

            log.debug("  partition{}: startLine={} | endLine={}", i, start, end);

            partitions.put("partition" + i, context);
            start = end;
        }

        log.info("FileRangePartitioner created {} partitions", partitions.size());
        return partitions;
    }

    private int countLines() {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(resource.getInputStream()))) {
            int count = (int) reader.lines().count();
            return Math.max(0, count - 1); // subtract header line
        } catch (IOException e) {
            throw new IllegalStateException("Failed to count lines in resource: " + resource, e);
        }
    }
}
