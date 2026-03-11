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

public class MultiFilePartitioner implements Partitioner {

    private static final Logger log = LoggerFactory.getLogger(MultiFilePartitioner.class);

    private final Path directory;

    public MultiFilePartitioner(Path directory) {
        this.directory = directory;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        log.info("MultiFilePartitioner scanning directory: {}", directory);

        Map<String, ExecutionContext> partitions = new HashMap<>();
        int index = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.csv")) {
            for (Path file : stream) {
                ExecutionContext context = new ExecutionContext();
                context.putString("filePath", file.toAbsolutePath().toString());
                context.putString("fileName", file.getFileName().toString());
                partitions.put("partition" + index, context);

                log.debug("  partition{}: fileName={} | filePath={}", index, file.getFileName(), file.toAbsolutePath());
                index++;
            }
        } catch (IOException e) {
            log.error("Failed to list CSV files in directory: {}", directory, e);
            throw new IllegalStateException(
                    "Failed to list CSV files in directory: " + directory, e);
        }

        log.info("MultiFilePartitioner created {} partitions from CSV files", partitions.size());
        return partitions;
    }
}
