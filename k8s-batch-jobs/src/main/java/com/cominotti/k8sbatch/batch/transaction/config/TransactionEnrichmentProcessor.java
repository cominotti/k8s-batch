// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction.config;

import com.cominotti.k8sbatch.avro.EnrichedTransactionEvent;
import com.cominotti.k8sbatch.avro.TransactionEvent;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Enriches a raw {@link TransactionEvent} with exchange rate conversion to USD and a risk score.
 *
 * <p>Lives in the config zone (not domain) because it implements the Spring Batch
 * {@link ItemProcessor} framework interface and maps between Avro wire-format types — both are
 * infrastructure concerns. Risk scoring and exchange rate lookup are delegated to
 * {@link EnrichmentRuleConstants}.
 *
 * <p>Uses {@code double} arithmetic because the Avro schema defines amounts and rates as
 * {@code double}. Converting to {@code BigDecimal} and back would add allocations per item
 * with no precision benefit in the batch hot path.
 */
public class TransactionEnrichmentProcessor
        implements ItemProcessor<TransactionEvent, EnrichedTransactionEvent> {

    private static final Logger log = LoggerFactory.getLogger(TransactionEnrichmentProcessor.class);

    private final EnrichmentRuleConstants ruleConstants;

    /**
     * Creates a processor using the default business rule constants.
     */
    public TransactionEnrichmentProcessor() {
        this(EnrichmentRuleConstants.DEFAULTS);
    }

    /**
     * Creates a processor with explicit rule constants (used for testing with custom thresholds).
     *
     * @param ruleConstants shared business rule constants for exchange rates and risk scoring
     */
    TransactionEnrichmentProcessor(EnrichmentRuleConstants ruleConstants) {
        this.ruleConstants = ruleConstants;
    }

    @Override
    public EnrichedTransactionEvent process(TransactionEvent event) {
        double exchangeRate = ruleConstants.rateFor(event.getCurrency()).doubleValue();
        double amountUsd = event.getAmount() * exchangeRate;
        String riskScore = ruleConstants.riskScoreFor(BigDecimal.valueOf(amountUsd)).name();

        log.debug("Enriching transaction | transactionId={} | currency={} | amount={}"
                        + " | exchangeRate={} | amountUsd={} | riskScore={}",
                event.getTransactionId(), event.getCurrency(), event.getAmount(),
                exchangeRate, amountUsd, riskScore);

        return EnrichedTransactionEvent.newBuilder()
                .setTransactionId(event.getTransactionId())
                .setAccountId(event.getAccountId())
                .setAmount(event.getAmount())
                .setCurrency(event.getCurrency())
                .setTimestamp(event.getTimestamp())
                .setExchangeRate(exchangeRate)
                .setAmountUsd(amountUsd)
                .setRiskScore(riskScore)
                .setProcessedAt(Instant.now().toEpochMilli())
                .build();
    }
}
