// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.config;

import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

/**
 * Spring Batch processor that delegates financial transaction enrichment to the active
 * {@link TransactionRulesEvaluator} implementation.
 *
 * <p>This processor is engine-agnostic — the concrete rules engine implementation is injected
 * via Spring's {@code @ConditionalOnProperty} mechanism based on the {@code batch.rules.engine}
 * property. It lives in the config zone (not domain) because it implements the Spring Batch
 * {@link ItemProcessor} framework interface.
 */
public class RulesEngineProcessor
        implements ItemProcessor<FinancialTransaction, EnrichedFinancialTransaction> {

    private static final Logger log = LoggerFactory.getLogger(RulesEngineProcessor.class);

    private final TransactionRulesEvaluator rulesEvaluator;

    /**
     * Creates a processor backed by the given rules evaluator.
     *
     * @param rulesEvaluator the active rules engine implementation
     */
    public RulesEngineProcessor(TransactionRulesEvaluator rulesEvaluator) {
        this.rulesEvaluator = rulesEvaluator;
        log.info("RulesEngineProcessor initialized | engine={}", rulesEvaluator.engineName());
    }

    @Override
    public EnrichedFinancialTransaction process(FinancialTransaction transaction) {
        log.debug("Evaluating rules | engine={} | transactionId={} | currency={} | amount={}",
                rulesEvaluator.engineName(), transaction.transactionId(),
                transaction.currency(), transaction.amount());

        return rulesEvaluator.evaluate(transaction);
    }
}
