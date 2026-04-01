// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.remote;

import com.cominotti.k8sbatch.avro.EnrichedTransactionEvent;
import com.cominotti.k8sbatch.avro.TransactionEvent;
import com.cominotti.k8sbatch.batch.transaction.adapters.streamingevents.kafka.TransactionTopicNames;
import com.cominotti.k8sbatch.it.AbstractBatchIntegrationTest;
import com.cominotti.k8sbatch.it.config.SharedContainersConfig;
import com.cominotti.k8sbatch.it.config.TransactionTestConfig;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the transaction enrichment job under Kafka-based remote partitioning (Redpanda).
 * Seeds Avro {@link TransactionEvent} messages to the input topic, runs the batch job, and
 * verifies enriched records in MySQL.
 *
 * <p>Note: {@code KafkaItemReader} starts from offset 0 on each job run, so messages accumulate
 * across tests. Tests use unique transaction IDs and verify by ID (not total count) for isolation.
 * DB is cleaned before each test via upsert idempotency — re-processing old messages just updates
 * existing rows.
 */
@Import({SharedContainersConfig.class, TransactionTestConfig.class})
@ActiveProfiles({"integration-test", "remote-partitioning", "remote-kafka"})
class TransactionEnrichmentRemoteIT extends AbstractBatchIntegrationTest {

    private static final String INPUT_TOPIC = TransactionTopicNames.TRANSACTION_EVENTS;
    private static final String OUTPUT_TOPIC = TransactionTopicNames.ENRICHED_TRANSACTION_EVENTS;

    @Autowired
    @Qualifier("transactionJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Autowired
    private KafkaTemplate<String, TransactionEvent> transactionEventTestProducer;

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    @Value("${spring.kafka.properties.schema.registry.url}")
    private String schemaRegistryUrl;

    @AfterEach
    void cleanupTransactionData() {
        jdbcTemplate.execute("DELETE FROM enriched_transactions");
    }

    @Test
    void shouldEnrichTransactionsEndToEnd() throws Exception {
        String prefix = UUID.randomUUID().toString().substring(0, 8);
        List<TransactionEvent> events = createTestEvents(5, "USD", 500.0, prefix);
        publishEvents(events);

        JobExecution execution = jobOperatorTestUtils.startJob(transactionJobParams());

        assertThat(execution.getStatus())
                .as("Job should complete. Exit: %s", execution.getExitStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        // Verify our specific 5 events were persisted (by prefix)
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enriched_transactions WHERE transaction_id LIKE ?",
                Integer.class, prefix + "%");
        assertThat(count).isEqualTo(5);
    }

    @Test
    void shouldCalculateCorrectExchangeRateAndRiskScore() throws Exception {
        String txId = "TX-EUR-" + UUID.randomUUID().toString().substring(0, 8);
        TransactionEvent event = TransactionEvent.newBuilder()
                .setTransactionId(txId)
                .setAccountId("ACC-001")
                .setAmount(5000.0)
                .setCurrency("EUR")
                .setTimestamp(Instant.now().toEpochMilli())
                .build();
        publishEvents(List.of(event));

        jobOperatorTestUtils.startJob(transactionJobParams());

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM enriched_transactions WHERE transaction_id = ?", txId);
        // EUR exchange rate is 1.08, so 5000 * 1.08 = 5400 USD
        assertThat(((Number) row.get("exchange_rate")).doubleValue()).isEqualTo(1.08);
        assertThat(((Number) row.get("amount_usd")).doubleValue())
                .isCloseTo(5400.0, org.assertj.core.data.Offset.offset(0.01));
        // 5400 USD is MEDIUM risk (1000 <= x < 10000)
        assertThat(row).containsEntry("risk_score", "MEDIUM");
    }

    @Test
    void shouldHandleUpsertOnDuplicateTransactionId() throws Exception {
        String txId = "TX-UPSERT-" + UUID.randomUUID().toString().substring(0, 8);
        TransactionEvent event1 = TransactionEvent.newBuilder()
                .setTransactionId(txId)
                .setAccountId("ACC-001")
                .setAmount(100.0)
                .setCurrency("USD")
                .setTimestamp(Instant.now().toEpochMilli())
                .build();
        TransactionEvent event2 = TransactionEvent.newBuilder()
                .setTransactionId(txId)
                .setAccountId("ACC-001")
                .setAmount(999.0)
                .setCurrency("USD")
                .setTimestamp(Instant.now().toEpochMilli())
                .build();
        publishEvents(List.of(event1, event2));

        jobOperatorTestUtils.startJob(transactionJobParams());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM enriched_transactions WHERE transaction_id = ?",
                Integer.class, txId);
        assertThat(count).isEqualTo(1);

        // The last event's amount should win due to ON DUPLICATE KEY UPDATE
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM enriched_transactions WHERE transaction_id = ?", txId);
        assertThat(((Number) row.get("amount")).doubleValue()).isEqualTo(999.0);
    }

    @Test
    void shouldCompleteWithNoNewRecordsOnRerun() throws Exception {
        // Verifies the job completes successfully even when re-reading already-processed events.
        // KafkaItemReader starts from offset 0, so accumulated events from previous tests are
        // re-processed — the ON DUPLICATE KEY UPDATE makes this idempotent.
        JobExecution execution = jobOperatorTestUtils.startJob(transactionJobParams());

        assertThat(execution.getStatus())
                .as("Job should complete. Exit: %s", execution.getExitStatus())
                .isEqualTo(BatchStatus.COMPLETED);
    }

    /**
     * Verifies that the transactional producer publishes committed messages to the output topic
     * and that a {@code read_committed} consumer can read exactly the expected number of records.
     *
     * <p>This test validates the Kafka transaction integration end-to-end:
     * <ol>
     *   <li>Publishes events to the input topic</li>
     *   <li>Runs the batch job (which writes to both DB and output Kafka topic transactionally)</li>
     *   <li>Creates a separate {@code read_committed} consumer on the output topic</li>
     *   <li>Verifies the consumer sees exactly the committed enriched events — no duplicates,
     *       no uncommitted/aborted records</li>
     * </ol>
     */
    @Test
    void shouldPublishCommittedEventsToOutputTopic() throws Exception {
        String prefix = "TX-COMMITTED-" + UUID.randomUUID().toString().substring(0, 8);
        List<TransactionEvent> events = createTestEvents(3, "USD", 100.0, prefix);
        publishEvents(events);

        JobExecution execution = jobOperatorTestUtils.startJob(transactionJobParams());
        assertThat(execution.getStatus())
                .as("Job should complete. Exit: %s", execution.getExitStatus())
                .isEqualTo(BatchStatus.COMPLETED);

        // Consume from the output topic with read_committed isolation to verify only committed
        // messages are visible. This mimics what a downstream consumer would see in production.
        List<EnrichedTransactionEvent> outputEvents = consumeOutputTopic(prefix, 3);
        assertThat(outputEvents)
                .as("Output topic should contain exactly the committed enriched events")
                .hasSize(3);

        // Verify each output event has the expected enrichment fields populated
        for (EnrichedTransactionEvent enriched : outputEvents) {
            assertThat(enriched.getTransactionId()).startsWith(prefix);
            assertThat(enriched.getExchangeRate()).isEqualTo(1.0); // USD rate
            assertThat(enriched.getRiskScore()).isNotNull();
            assertThat(enriched.getProcessedAt()).isGreaterThan(0);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /**
     * Consumes enriched events from the output topic using a read_committed consumer.
     * Filters by transaction ID prefix to isolate this test's events from others.
     * Exits early once {@code expectedCount} matching events are found.
     */
    private List<EnrichedTransactionEvent> consumeOutputTopic(String txIdPrefix, int expectedCount) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-output-consumer-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);
        // read_committed: only see messages from committed Kafka transactions
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");

        List<EnrichedTransactionEvent> results = new ArrayList<>();
        try (KafkaConsumer<String, EnrichedTransactionEvent> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(OUTPUT_TOPIC));
            int emptyPolls = 0;
            while (emptyPolls < 5) {
                ConsumerRecords<String, EnrichedTransactionEvent> records =
                        consumer.poll(Duration.ofSeconds(2));
                if (records.isEmpty()) {
                    emptyPolls++;
                } else {
                    emptyPolls = 0;
                    records.forEach(consumerRecord -> {
                        if (consumerRecord.value().getTransactionId().startsWith(txIdPrefix)) {
                            results.add(consumerRecord.value());
                        }
                    });
                    if (results.size() >= expectedCount) {
                        break;
                    }
                }
            }
        }
        return results;
    }

    private static JobParameters transactionJobParams() {
        return new JobParametersBuilder()
                .addString("kafka.partitions", "0")
                .addLong("timestamp", System.currentTimeMillis())
                .toJobParameters();
    }

    private List<TransactionEvent> createTestEvents(
            int count, String currency, double amount, String idPrefix) {
        List<TransactionEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(TransactionEvent.newBuilder()
                    .setTransactionId(idPrefix + "-" + i)
                    .setAccountId("ACC-" + i)
                    .setAmount(amount + i)
                    .setCurrency(currency)
                    .setTimestamp(Instant.now().toEpochMilli())
                    .build());
        }
        return events;
    }

    private void publishEvents(List<TransactionEvent> events)
            throws ExecutionException, InterruptedException, TimeoutException {
        // Collect futures and await after all sends to leverage Kafka batching
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (TransactionEvent event : events) {
            futures.add(transactionEventTestProducer
                    .send(INPUT_TOPIC, event.getTransactionId(), event).toCompletableFuture());
        }
        transactionEventTestProducer.flush();
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
                .get(30, TimeUnit.SECONDS);
    }
}
