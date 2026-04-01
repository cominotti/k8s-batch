// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.droolsruleunit;

import com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.TransactionFact;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;
import org.drools.ruleunits.api.DataSource;
import org.drools.ruleunits.api.DataStore;
import org.drools.ruleunits.api.RuleUnitInstance;
import org.drools.ruleunits.api.RuleUnitProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Drools rule-unit implementation of {@link TransactionRulesEvaluator}.
 *
 * <p>Splits enrichment into two phases:
 * <ol>
 *   <li><strong>Java (sequential)</strong>: exchange rate lookup and USD conversion via
 *       {@link EnrichmentRuleConstants} — straightforward computations that don't benefit
 *       from a rules engine.</li>
 *   <li><strong>Rule unit (tabular decisions)</strong>: risk scoring and compliance flagging
 *       via {@link TransactionEnrichmentUnit} backed by a DRL with OOPath syntax. The rule
 *       unit scopes data access — rules only see the unit's {@code DataStore} and constants,
 *       not a shared global namespace.</li>
 * </ol>
 *
 * <p>Unlike the classic {@code DroolsTransactionRulesEvaluator} which uses {@code KieSession}
 * and globals, this adapter uses {@link RuleUnitProvider} for typed, self-contained rule
 * execution — the model that scales to 50-200 rules via unit composition.
 */
@Component
@ConditionalOnProperty(name = "batch.rules.engine", havingValue = "drools-ruleunit")
public class DroolsRuleUnitTransactionRulesEvaluator implements TransactionRulesEvaluator {

    private static final Logger log = LoggerFactory.getLogger(
            DroolsRuleUnitTransactionRulesEvaluator.class);

    private final EnrichmentRuleConstants ruleConstants;

    /**
     * Initializes with {@link EnrichmentRuleConstants#DEFAULTS default} business rule constants.
     */
    public DroolsRuleUnitTransactionRulesEvaluator() {
        this(EnrichmentRuleConstants.DEFAULTS);
    }

    /**
     * Initializes with explicit rule constants (used for testing with custom thresholds).
     *
     * @param ruleConstants shared business rule constants for exchange rate lookup and
     *                      rule unit field injection
     */
    DroolsRuleUnitTransactionRulesEvaluator(EnrichmentRuleConstants ruleConstants) {
        log.info("Initializing Drools rule-unit evaluator");
        this.ruleConstants = ruleConstants;
        log.info("Drools rule-unit evaluator initialized");
    }

    @Override
    public EnrichedFinancialTransaction evaluate(FinancialTransaction transaction) {
        TransactionFact fact = TransactionFact.from(transaction);

        // Phase 1: Java — exchange rate lookup + USD conversion
        fact.setExchangeRate(ruleConstants.rateFor(fact.getCurrency()));
        fact.setAmountUsd(fact.getAmount().multiply(fact.getExchangeRate()));

        // Phase 2: Rule unit — risk scoring + compliance flagging (tabular decisions)
        DataStore<TransactionFact> store = DataSource.createStore();
        store.add(fact);

        TransactionEnrichmentUnit unit = new TransactionEnrichmentUnit(store, ruleConstants);

        try (RuleUnitInstance<TransactionEnrichmentUnit> instance =
                     RuleUnitProvider.get().createRuleUnitInstance(unit)) {
            instance.fire();
        }

        return fact.toEnrichedTransaction(engineName(), Instant.now());
    }

    @Override
    public String engineName() {
        return "drools-ruleunit";
    }
}
