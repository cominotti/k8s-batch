// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.domain;

/**
 * Port interface for evaluating financial transaction business rules.
 *
 * <p>Implementations apply exchange rate conversion, risk scoring, and compliance flagging to a
 * raw {@link FinancialTransaction}, producing an {@link EnrichedFinancialTransaction}. The active
 * implementation is selected by the {@code batch.rules.engine} property.
 */
public interface TransactionRulesEvaluator {

    /**
     * Evaluates all business rules against the given transaction and returns the enriched result.
     *
     * @param transaction raw financial transaction to evaluate
     * @return enriched transaction with computed exchange rate, USD amount, risk score, and
     *         compliance flag
     */
    EnrichedFinancialTransaction evaluate(FinancialTransaction transaction);

    /**
     * Returns the name of the rules engine implementation (e.g. "drools" or "evrete").
     *
     * @return engine identifier, stored in the output for traceability
     */
    String engineName();
}
