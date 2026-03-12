// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction.adapters;

import com.cominotti.k8sbatch.avro.EnrichedTransactionEvent;
import com.cominotti.k8sbatch.avro.TransactionEvent;
import com.cominotti.k8sbatch.batch.transaction.domain.TransactionJobProperties;
import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroDeserializer;
import io.confluent.kafka.serializers.KafkaAvroDeserializerConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka factories for the transaction enrichment job using Avro serialization with Schema Registry.
 *
 * <p>These factories are completely separate from the {@code RemotePartitioningJobConfig} factories
 * which use {@code ByteArraySerializer/Deserializer} for {@code StepExecutionRequest} messages.
 * Bean names are explicitly qualified to avoid autowiring collisions.
 *
 * <h3>Kafka Transaction Support</h3>
 *
 * <p>When {@code batch.transaction.kafka-transactions-enabled=true}, the producer factory is
 * configured with a {@code transactionIdPrefix}, which activates two Kafka features:
 * <ul>
 *   <li><strong>Idempotent producer</strong> ({@code enable.idempotence=true}): the broker
 *       deduplicates retried sends using a per-partition sequence number, preventing duplicates
 *       from network retries within a single producer session.</li>
 *   <li><strong>Transactional producer</strong>: sends are grouped into atomic transactions.
 *       Consumers with {@code isolation.level=read_committed} only see messages after the
 *       transaction commits, never uncommitted or aborted messages.</li>
 * </ul>
 *
 * <p>The Kafka transaction is synchronized with Spring's {@code DataSourceTransactionManager}
 * automatically. When {@code KafkaTemplate.send()} is called within a Spring transaction (the
 * chunk's DB transaction), Spring Kafka's {@code ProducerFactoryUtils} detects the transactional
 * {@code ProducerFactory} and registers a {@code TransactionSynchronization} that:
 * <ol>
 *   <li>Begins the Kafka transaction on the first {@code send()}</li>
 *   <li>Commits the Kafka transaction after the Spring (DB) transaction commits</li>
 *   <li>Aborts the Kafka transaction if the Spring transaction rolls back</li>
 * </ol>
 *
 * <p>This is "best-effort one-phase commit" — the standard production approach for DB + Kafka.
 * The only failure window is a crash after DB commit but before Kafka commit. On recovery:
 * <ul>
 *   <li>The Kafka transaction is aborted (uncommitted messages are invisible to
 *       {@code read_committed} consumers)</li>
 *   <li>Spring Batch retries the chunk, the DB upsert ({@code ON DUPLICATE KEY UPDATE}) handles
 *       the already-committed DB rows, and Kafka re-sends the messages in a new transaction</li>
 * </ul>
 */
@Configuration
@Profile("remote-partitioning")
public class TransactionKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(TransactionKafkaConfig.class);

    /**
     * Transaction ID prefix for the Kafka transactional producer. Each producer instance appends
     * a unique suffix (e.g., {@code txn-enrichment-0}, {@code txn-enrichment-1}) to support
     * concurrent transactional producers across horizontally-scaled pod replicas.
     */
    private static final String TRANSACTION_ID_PREFIX = "txn-enrichment-";

    private final TransactionJobProperties jobProperties;
    private final String bootstrapServers;
    private final String schemaRegistryUrl;

    /**
     * Injects Kafka connection properties and job configuration.
     *
     * @param jobProperties    topic names, consumer group, and transaction settings
     * @param bootstrapServers Kafka broker addresses
     * @param schemaRegistryUrl Confluent Schema Registry URL for Avro serialization
     */
    public TransactionKafkaConfig(
            TransactionJobProperties jobProperties,
            @Value("${spring.kafka.bootstrap-servers:localhost:9092}") String bootstrapServers,
            @Value("${spring.kafka.properties.schema.registry.url:http://localhost:8081}") String schemaRegistryUrl) {
        this.jobProperties = jobProperties;
        this.bootstrapServers = bootstrapServers;
        this.schemaRegistryUrl = schemaRegistryUrl;
        log.info("TransactionKafkaConfig initialized | bootstrapServers={} | schemaRegistryUrl={}"
                        + " | inputTopic={} | outputTopic={} | kafkaTransactionsEnabled={}",
                bootstrapServers, schemaRegistryUrl, jobProperties.inputTopic(),
                jobProperties.outputTopic(), jobProperties.kafkaTransactionsEnabled());
    }

    /**
     * Consumer factory for reading raw {@link TransactionEvent} messages from the input topic.
     *
     * <p>When Kafka transactions are enabled, the consumer is configured with
     * {@code isolation.level=read_committed}. This means the consumer will only see messages
     * that have been committed by a transactional producer — uncommitted or aborted transaction
     * messages are filtered out at the broker level using the Last Stable Offset (LSO).
     * Non-transactional messages are unaffected and always visible.
     *
     * <p>This isolation level is important for downstream consumers of the output topic, but we
     * also apply it to the input consumer for consistency — if the input topic is fed by another
     * transactional producer, we want the same read-committed guarantee.
     *
     * @return consumer factory configured with Avro deserialization and Schema Registry
     */
    @Bean
    public ConsumerFactory<String, TransactionEvent> transactionConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, jobProperties.consumerGroup());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, KafkaAvroDeserializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        // Returns generated TransactionEvent (SpecificRecord), not GenericRecord
        props.put(KafkaAvroDeserializerConfig.SPECIFIC_AVRO_READER_CONFIG, true);

        if (jobProperties.kafkaTransactionsEnabled()) {
            // read_committed: only consume messages from committed transactions.
            // Default is read_uncommitted, which sees all messages regardless of transaction state.
            props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
            log.info("Consumer configured with isolation.level=read_committed");
        }

        return new DefaultKafkaConsumerFactory<>(props);
    }

    /**
     * Producer factory for publishing enriched {@link EnrichedTransactionEvent} messages.
     *
     * <p>When {@code kafkaTransactionsEnabled} is {@code true}:
     * <ul>
     *   <li>{@code enable.idempotence} is set explicitly (Kafka 3.x defaults it to true for
     *       transactional producers, but being explicit aids clarity and compatibility)</li>
     *   <li>{@code setTransactionIdPrefix()} activates the transactional producer — all sends
     *       through this factory's producers will participate in Kafka transactions</li>
     * </ul>
     *
     * <p>Performance note: transactional producers add ~30% overhead due to extra broker
     * round-trips ({@code InitProducerId}, {@code AddPartitionsToTxn}, {@code EndTxn}).
     * For high-throughput batch jobs, this is generally acceptable given the consistency guarantee.
     *
     * @return producer factory, optionally transactional based on {@code kafkaTransactionsEnabled}
     */
    @Bean
    public ProducerFactory<String, EnrichedTransactionEvent> transactionProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);

        if (jobProperties.kafkaTransactionsEnabled()) {
            // Idempotent producer: the broker tracks a producer ID + sequence number per partition,
            // deduplicating retried sends. Required for transactional producers.
            props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        }

        DefaultKafkaProducerFactory<String, EnrichedTransactionEvent> factory =
                new DefaultKafkaProducerFactory<>(props);

        if (jobProperties.kafkaTransactionsEnabled()) {
            // Setting a transactionIdPrefix activates the transactional producer. Spring Kafka
            // will call initTransactions() on the first send and manage begin/commit/abort
            // lifecycle via TransactionSynchronizationManager when called within a Spring transaction.
            factory.setTransactionIdPrefix(TRANSACTION_ID_PREFIX);
            log.info("Kafka transactions enabled | transactionIdPrefix={}", TRANSACTION_ID_PREFIX);
        }

        return factory;
    }

    @Bean
    public KafkaTemplate<String, EnrichedTransactionEvent> transactionKafkaTemplate(
            ProducerFactory<String, EnrichedTransactionEvent> transactionProducerFactory) {
        KafkaTemplate<String, EnrichedTransactionEvent> template =
                new KafkaTemplate<>(transactionProducerFactory);
        // KafkaItemWriter requires a default topic on the template
        template.setDefaultTopic(jobProperties.outputTopic());
        return template;
    }
}
