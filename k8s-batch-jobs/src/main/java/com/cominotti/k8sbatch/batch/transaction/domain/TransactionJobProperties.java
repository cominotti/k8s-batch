// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction.domain;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Transaction enrichment job configuration bound to the {@code batch.transaction.*} properties.
 *
 * @param inputTopic               Kafka topic to consume raw {@code TransactionEvent} messages from
 * @param outputTopic              Kafka topic to publish enriched {@code EnrichedTransactionEvent} messages to
 * @param consumerGroup            Kafka consumer group ID for the KafkaItemReader
 * @param chunkSize                items per transaction in the chunk-oriented step
 * @param pollTimeoutSeconds       KafkaItemReader poll timeout — how long to wait for new records
 *                                 before considering the topic exhausted. Lower values speed up tests
 * @param kafkaTransactionsEnabled whether to enable Kafka transactional producer for coordinated
 *                                 DB + Kafka writes. When {@code true}, the {@code ProducerFactory} is
 *                                 configured with a {@code transactionIdPrefix}, which activates Kafka's
 *                                 idempotent producer and transactional semantics. The Kafka transaction
 *                                 automatically piggybacks on the chunk's Spring {@code DataSourceTransactionManager}
 *                                 via {@code TransactionSynchronizationManager} — no separate
 *                                 {@code KafkaTransactionManager} is needed.
 *
 *                                 <p>This is "best-effort one-phase commit": the DB commits first, then
 *                                 the Kafka transaction commits. A crash between the two leaves a narrow
 *                                 window where the DB has the record but Kafka does not. On retry, the
 *                                 upsert ({@code ON DUPLICATE KEY UPDATE}) prevents DB duplicates, and the
 *                                 Kafka producer's idempotence prevents Kafka duplicates within a session.
 *
 *                                 <p>When {@code false} (default), the producer is non-transactional —
 *                                 simpler and ~30% faster, but DB and Kafka writes are not coordinated.
 */
@ConfigurationProperties(prefix = "batch.transaction")
public record TransactionJobProperties(
        String inputTopic,
        String outputTopic,
        String consumerGroup,
        int chunkSize,
        int pollTimeoutSeconds,
        boolean kafkaTransactionsEnabled
) {

    public TransactionJobProperties {
        if (inputTopic == null || inputTopic.isBlank()) {
            inputTopic = TransactionTopicNames.TRANSACTION_EVENTS;
        }
        if (outputTopic == null || outputTopic.isBlank()) {
            outputTopic = TransactionTopicNames.ENRICHED_TRANSACTION_EVENTS;
        }
        if (consumerGroup == null || consumerGroup.isBlank()) {
            consumerGroup = "k8s-batch-transaction-processor";
        }
        if (chunkSize <= 0) {
            chunkSize = 100;
        }
        if (pollTimeoutSeconds <= 0) {
            pollTimeoutSeconds = 5;
        }
        // kafkaTransactionsEnabled defaults to false (boolean primitive default) — no validation needed
    }
}
