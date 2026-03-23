// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.e2e.batch;

import com.cominotti.k8sbatch.batch.transaction.domain.TransactionTopicNames;
import com.cominotti.k8sbatch.e2e.AbstractE2ETest;
import com.cominotti.k8sbatch.e2e.client.BatchAppClient.JobResponse;
import com.cominotti.k8sbatch.e2e.client.KafkaEventSeeder;
import com.cominotti.k8sbatch.e2e.cluster.K3sClusterManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E test for the transaction enrichment job deployed via Helm into a K3s cluster.
 * Seeds Avro TransactionEvent messages via a Kubernetes Job (kafka-avro-console-producer),
 * then launches the batch job via REST API and verifies enriched records in MySQL.
 */
class TransactionEnrichmentJobE2E extends AbstractE2ETest {

    /** {@inheritDoc} Deploys the full stack — Kafka is both the event source and output sink. */
    @Override
    protected String valuesFile() {
        return "e2e-remote.yaml";
    }

    /** {@inheritDoc} Kafka is required as the source of TransactionEvent messages. */
    @Override
    protected boolean requiresKafka() {
        return true;
    }

    /**
     * Cleans enriched transaction data before each test, in addition to the base class's
     * {@code target_records} cleanup. Both tables must be empty to verify correct row counts.
     */
    @BeforeEach
    void cleanTransactionData() throws Exception {
        if (mysqlVerifier != null) {
            mysqlVerifier.cleanEnrichedTransactions();
        }
    }

    /**
     * Verifies that the {@code enriched_transactions} table exists, confirming that
     * Flyway migration V2 ran successfully during application startup.
     */
    @Test
    void shouldCreateEnrichedTransactionsTable() throws Exception {
        assertThat(mysqlVerifier.tableExists("enriched_transactions"))
                .as("Liquibase migration should create enriched_transactions table")
                .isTrue();
    }

    /**
     * Full end-to-end test: seeds 5 Avro TransactionEvent messages into Kafka via a K8s Job
     * running {@code kafka-avro-console-producer}, launches the enrichment batch job via
     * the REST API, and verifies that all 5 events are enriched (exchange rate + risk score)
     * and persisted to the {@code enriched_transactions} MySQL table.
     */
    @Test
    void shouldProcessTransactionEventsEndToEnd() throws Exception {
        // Seed 5 test events via K8s Job (kafka-avro-console-producer inside K3s)
        List<String> eventsJson = createTestEventsJson(5);
        KafkaEventSeeder.seedEvents(
                K3sClusterManager.client(), K3sClusterManager.namespace(),
                TransactionTopicNames.TRANSACTION_EVENTS, eventsJson);

        // Launch the batch job via REST API
        JobResponse result = appClient.launchJobAndWaitForCompletion(
                "transactionEnrichmentJob",
                Map.of("kafka.partitions", "0"),
                Duration.ofMinutes(3));

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.exitCode()).isEqualTo("COMPLETED");

        // Verify enriched data in MySQL
        int recordCount = mysqlVerifier.countEnrichedTransactions();
        assertThat(recordCount)
                .as("All 5 transaction events should be enriched and persisted")
                .isEqualTo(5);
    }

    /**
     * Generates test TransactionEvent JSON objects with random UUIDs and sequential amounts.
     * Each event has a unique transactionId (UUID), sequential accountId, increasing amount,
     * USD currency, and the current timestamp. The JSON format matches the Avro schema
     * expected by {@code kafka-avro-console-producer}.
     *
     * @param count number of test events to generate
     * @return list of JSON strings, one per event
     */
    private List<String> createTestEventsJson(int count) {
        List<String> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String json = String.format(
                    "{\"transactionId\":\"%s\",\"accountId\":\"ACC-%d\","
                    + "\"amount\":%.1f,\"currency\":\"USD\",\"timestamp\":%d}",
                    UUID.randomUUID().toString(), i, 100.0 + i,
                    Instant.now().toEpochMilli());
            events.add(json);
        }
        return events;
    }
}
