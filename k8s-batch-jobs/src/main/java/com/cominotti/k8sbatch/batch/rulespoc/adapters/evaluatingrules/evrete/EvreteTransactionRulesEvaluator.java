// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.evrete;

import com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.TransactionFact;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;
import org.evrete.KnowledgeService;
import org.evrete.api.Knowledge;
import org.evrete.api.StatefulSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;
import java.time.Instant;

/**
 * EVRete-based implementation of {@link TransactionRulesEvaluator}.
 *
 * <p>Defines business rules programmatically using EVRete's Java fluent API. Rules are compiled
 * into a {@link Knowledge} object at startup. Each {@link #evaluate} call creates a new
 * {@link StatefulSession}, inserts the transaction fact, fires rules, and reads back enriched
 * fields.
 *
 * <p>Business rule constants (exchange rates, thresholds) are sourced from
 * {@link EnrichmentRuleConstants} — the same values used by the Drools DRL. EVRete uses the RETE
 * algorithm with zero external dependencies, making it the lightest rules engine option.
 */
@Component
@ConditionalOnProperty(name = "batch.rules.engine", havingValue = "evrete")
public class EvreteTransactionRulesEvaluator implements TransactionRulesEvaluator {

    private static final Logger log = LoggerFactory.getLogger(EvreteTransactionRulesEvaluator.class);

    private final KnowledgeService knowledgeService;
    private final Knowledge knowledge;
    private final EnrichmentRuleConstants ruleConstants;

    /**
     * Initializes the EVRete knowledge service and compiles rules using the fluent Java API.
     * Rule constants (exchange rates, risk thresholds) are sourced from the shared domain record.
     */
    @Autowired
    public EvreteTransactionRulesEvaluator() {
        this(EnrichmentRuleConstants.DEFAULTS);
    }

    /**
     * Initializes with explicit rule constants (used for testing with custom thresholds).
     *
     * @param ruleConstants shared business rule constants
     */
    EvreteTransactionRulesEvaluator(EnrichmentRuleConstants ruleConstants) {
        log.info("Initializing EVRete rules evaluator");

        this.ruleConstants = ruleConstants;
        this.knowledgeService = new KnowledgeService();
        this.knowledge = knowledgeService
                .newKnowledge()
                .builder()
                .newRule("Exchange Rate Lookup")
                .forEach("$tx", TransactionFact.class)
                .where("$tx.exchangeRate == null")
                .execute(ctx -> {
                    TransactionFact tx = ctx.get("$tx");
                    tx.setExchangeRate(ruleConstants.rateFor(tx.getCurrency()));
                    ctx.update(tx);
                })
                .newRule("Convert to USD")
                .forEach("$tx", TransactionFact.class)
                .where("$tx.exchangeRate != null && $tx.amountUsd == null")
                .execute(ctx -> {
                    TransactionFact tx = ctx.get("$tx");
                    tx.setAmountUsd(tx.getAmount().multiply(tx.getExchangeRate()));
                    ctx.update(tx);
                })
                .newRule("Risk Score Assignment")
                .forEach("$tx", TransactionFact.class)
                .where("$tx.amountUsd != null && $tx.riskScore == null")
                .execute(ctx -> {
                    TransactionFact tx = ctx.get("$tx");
                    tx.setRiskScore(ruleConstants.riskScoreFor(tx.getAmountUsd()));
                    ctx.update(tx);
                })
                .newRule("Compliance Flag")
                .forEach("$tx", TransactionFact.class)
                .where("$tx.riskScore != null && !$tx.complianceFlag")
                .execute(ctx -> {
                    TransactionFact tx = ctx.get("$tx");
                    if (ruleConstants.requiresComplianceReview(tx.getRiskScore(), tx.getAmountUsd())) {
                        tx.setComplianceFlag(true);
                        ctx.update(tx);
                    }
                })
                .build();

        log.info("EVRete rules evaluator initialized successfully");
    }

    @Override
    public EnrichedFinancialTransaction evaluate(FinancialTransaction transaction) {
        TransactionFact fact = TransactionFact.from(transaction);

        try (StatefulSession session = knowledge.newStatefulSession()) {
            session.insert(fact);
            session.fire();
        }

        return fact.toEnrichedTransaction(engineName(), Instant.now());
    }

    @Override
    public String engineName() {
        return "evrete";
    }

    /**
     * Shuts down the EVRete knowledge service on bean destruction.
     */
    @PreDestroy
    void shutdown() {
        log.info("Shutting down EVRete knowledge service");
        knowledgeService.shutdown();
    }
}
