package com.cominotti.k8sbatch.batch.multifile;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class MultiFilePartitioner implements Partitioner {

    private final Path directory;

    public MultiFilePartitioner(Path directory) {
        this.directory = directory;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        Map<String, ExecutionContext> partitions = new HashMap<>();
        int index = 0;

        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, "*.csv")) {
            for (Path file : stream) {
                ExecutionContext context = new ExecutionContext();
                context.putString("filePath", file.toAbsolutePath().toString());
                context.putString("fileName", file.getFileName().toString());
                partitions.put("partition" + index, context);
                index++;
            }
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to list CSV files in directory: " + directory, e);
        }

        return partitions;
    }
}
