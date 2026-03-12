// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction;

/**
 * Single source of truth for Kafka topic name constants used by the transaction enrichment job.
 * Referenced by production config defaults, integration test topic creation, and E2E test seeding.
 */
public final class TransactionTopicNames {

    public static final String TRANSACTION_EVENTS = "transaction-events";
    public static final String ENRICHED_TRANSACTION_EVENTS = "enriched-transaction-events";

    private TransactionTopicNames() {
    }
}
