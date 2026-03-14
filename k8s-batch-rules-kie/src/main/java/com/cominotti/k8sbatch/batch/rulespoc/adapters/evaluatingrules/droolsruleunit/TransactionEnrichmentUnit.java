// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.droolsruleunit;

import com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.TransactionFact;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitData;

/**
 * Rule unit that groups the data sources and shared constants for transaction enrichment.
 *
 * <p>This unit scopes the rules that operate on financial transactions: the DRL accesses
 * {@code transactions} via OOPath ({@code /transactions[...]}) and {@code ruleConstants} as a
 * plain field in {@code then} blocks. Each unit instance is self-contained — no globals, no
 * shared session state.
 *
 * <p>Designed for future decomposition: as the rule set grows (50-200 rules), this single unit
 * can be split into focused units (e.g. {@code RiskScoringUnit}, {@code ComplianceUnit}) with
 * explicit typed data contracts between them.
 *
 * @see DroolsRuleUnitTransactionRulesEvaluator
 */
public class TransactionEnrichmentUnit implements RuleUnitData {

    private final DataStore<TransactionFact> transactions;
    private final EnrichmentRuleConstants ruleConstants;

    /**
     * Creates a unit with an empty data store and
     * {@link EnrichmentRuleConstants#DEFAULTS default} constants.
     */
    public TransactionEnrichmentUnit() {
        this(DataSource.createStore(), EnrichmentRuleConstants.DEFAULTS);
    }

    /**
     * Creates a unit with explicit data store and constants (used for testing).
     *
     * @param transactions data store for transaction facts
     * @param ruleConstants shared business rule constants
     */
    public TransactionEnrichmentUnit(DataStore<TransactionFact> transactions,
                                     EnrichmentRuleConstants ruleConstants) {
        this.transactions = transactions;
        this.ruleConstants = ruleConstants;
    }

    /**
     * Returns the data store backing the {@code /transactions} OOPath root in DRL.
     *
     * @return transaction fact data store
     */
    public DataStore<TransactionFact> getTransactions() {
        return transactions;
    }

    /**
     * Returns the shared rule constants, accessible as {@code ruleConstants} in DRL
     * {@code then} blocks.
     *
     * @return enrichment rule constants
     */
    public EnrichmentRuleConstants getRuleConstants() {
        return ruleConstants;
    }
}
