package com.cominotti.k8sbatch.config;

import com.cominotti.k8sbatch.batch.filerange.FileRangePartitioner;
import com.cominotti.k8sbatch.batch.multifile.MultiFilePartitioner;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.batch.integration.partition.MessageChannelPartitionHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.MessagingTemplate;

@Configuration
@Profile("remote-partitioning")
public class RemotePartitioningJobConfig {

    @Value("${batch.partition.grid-size:4}")
    private int gridSize;

    // --- File-Range Job: Manager Step ---

    @Bean
    public MessageChannelPartitionHandler fileRangePartitionHandler(
            DirectChannel outboundRequests,
            QueueChannel inboundReplies) {
        MessageChannelPartitionHandler handler = new MessageChannelPartitionHandler();
        handler.setStepName("fileRangeWorkerStep");
        handler.setGridSize(gridSize);
        handler.setReplyChannel(inboundReplies);

        MessagingTemplate template = new MessagingTemplate();
        template.setDefaultChannel(outboundRequests);
        template.setReceiveTimeout(120_000);
        handler.setMessagingOperations(template);

        return handler;
    }

    @Bean
    public Step fileRangeManagerStep(
            JobRepository jobRepository,
            FileRangePartitioner fileRangePartitioner,
            MessageChannelPartitionHandler fileRangePartitionHandler) {
        return new StepBuilder("fileRangeManagerStep", jobRepository)
                .partitioner("fileRangeWorkerStep", fileRangePartitioner)
                .partitionHandler(fileRangePartitionHandler)
                .build();
    }

    // --- Multi-File Job: Manager Step ---

    @Bean
    public MessageChannelPartitionHandler multiFilePartitionHandler(
            DirectChannel outboundRequests,
            QueueChannel inboundReplies) {
        MessageChannelPartitionHandler handler = new MessageChannelPartitionHandler();
        handler.setStepName("multiFileWorkerStep");
        handler.setGridSize(gridSize);
        handler.setReplyChannel(inboundReplies);

        MessagingTemplate template = new MessagingTemplate();
        template.setDefaultChannel(outboundRequests);
        template.setReceiveTimeout(120_000);
        handler.setMessagingOperations(template);

        return handler;
    }

    @Bean
    public Step multiFileManagerStep(
            JobRepository jobRepository,
            MultiFilePartitioner multiFilePartitioner,
            MessageChannelPartitionHandler multiFilePartitionHandler) {
        return new StepBuilder("multiFileManagerStep", jobRepository)
                .partitioner("multiFileWorkerStep", multiFilePartitioner)
                .partitionHandler(multiFilePartitionHandler)
                .build();
    }
}
