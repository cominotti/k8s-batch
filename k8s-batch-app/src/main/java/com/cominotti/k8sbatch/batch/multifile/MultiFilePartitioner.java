// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.multifile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Creates one partition per CSV file found in a directory for parallel processing.
 *
 * <p>Implements the Spring Batch {@link Partitioner} contract: {@link #partition(int)} returns a
 * map of {@link ExecutionContext} objects. Unlike {@link com.cominotti.k8sbatch.batch.filerange.FileRangePartitioner
 * FileRangePartitioner}, the {@code gridSize} parameter is deliberately ignored — the partition
 * count equals the number of {@code *.csv} files in the directory. Each context carries
 * {@code filePath} (full path) and {@code fileName} (name only), injected into worker beans via
 * {@code @Value("#{stepExecutionContext['...']}}")} in {@code MultiFileJobConfig}.
 */
public class MultiFilePartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(MultiFilePartitioner.class);

    private final Path directory;

    /**
     * Creates a partitioner that scans the given directory for CSV files.
     *
     * @param directory directory containing {@code *.csv} files (no subdirectory recursion)
     */
    public MultiFilePartitioner(Path directory) {
        this.directory = directory;
    }

    /**
     * Creates one partition per CSV file. The {@code gridSize} parameter is ignored — file count
     * drives parallelism. Only top-level {@code *.csv} files are matched (no subdirectory recursion).
     */
    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        log.info("MultiFilePartitioner scanning directory: {}", directory);

        Map<String, ExecutionContext> partitions = new HashMap<>();
        int index = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.csv")) {
            for (Path file : stream) {
                ExecutionContext context = new ExecutionContext();
                // These key names must exactly match the @Value SpEL expressions in MultiFileJobConfig
                context.putString("filePath", file.toAbsolutePath().toString());
                context.putString("fileName", file.getFileName().toString());
                partitions.put("partition" + index, context);

                log.debug("  partition{}: fileName={} | filePath={}", index, file.getFileName(), file.toAbsolutePath());
                index++;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to list CSV files in directory: " + directory, e);
        }

        log.info("MultiFilePartitioner created {} partitions from CSV files", partitions.size());
        return partitions;
    }
}
