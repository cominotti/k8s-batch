// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction.adapters.streamingevents.kafka;

/**
 * Single source of truth for Kafka topic name constants used by the transaction enrichment job.
 * Referenced by production config defaults, integration test topic creation, and E2E test seeding.
 *
 * <p>Lives in the Kafka adapter package because topic names are infrastructure routing decisions,
 * not business domain concepts.
 */
public final class TransactionTopicNames {

    public static final String TRANSACTION_EVENTS = "transaction-events";
    public static final String ENRICHED_TRANSACTION_EVENTS = "enriched-transaction-events";

    private TransactionTopicNames() {
    }
}
