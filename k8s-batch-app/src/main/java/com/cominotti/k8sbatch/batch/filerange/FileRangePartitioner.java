package com.cominotti.k8sbatch.batch.filerange;

import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.core.io.Resource;

import org.springframework.core.io.FileSystemResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

public class FileRangePartitioner implements Partitioner {

    private final Resource resource;

    public FileRangePartitioner(Resource resource) {
        this.resource = resource;
    }

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        int totalLines = countLines();
        int linesPerPartition = Math.max(1, (int) Math.ceil((double) totalLines / gridSize));

        Map<String, ExecutionContext> partitions = new HashMap<>();
        int start = 0;

        for (int i = 0; i < gridSize && start < totalLines; i++) {
            ExecutionContext context = new ExecutionContext();
            int end = Math.min(start + linesPerPartition, totalLines);

            context.putInt("startLine", start);
            context.putInt("endLine", end);
            context.putString("resourcePath", ((FileSystemResource) resource).getPath());

            partitions.put("partition" + i, context);
            start = end;
        }

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
