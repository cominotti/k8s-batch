// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.transaction.domain;

import com.cominotti.k8sbatch.avro.EnrichedTransactionEvent;
import com.cominotti.k8sbatch.avro.TransactionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.batch.infrastructure.item.ItemProcessor;

import java.time.Instant;
import java.util.Map;

/**
 * Enriches a raw {@link TransactionEvent} with exchange rate conversion to USD and a risk score.
 *
 * <p>Exchange rates are static (hardcoded) for deterministic test predictability. In production,
 * this would be replaced with an external FX rate service call. Risk scoring uses simple USD
 * amount thresholds: LOW (&lt;1000), MEDIUM (&lt;10000), HIGH (&ge;10000).
 */
public class TransactionEnrichmentProcessor
        implements ItemProcessor<TransactionEvent, EnrichedTransactionEvent> {

    private static final Logger log = LoggerFactory.getLogger(TransactionEnrichmentProcessor.class);

    private static final Map<String, Double> EXCHANGE_RATES = Map.of(
            "USD", 1.0,
            "EUR", 1.08,
            "GBP", 1.27,
            "JPY", 0.0067,
            "BRL", 0.18
    );

    private static final double DEFAULT_EXCHANGE_RATE = 1.0;

    @Override
    public EnrichedTransactionEvent process(TransactionEvent event) {
        double exchangeRate = EXCHANGE_RATES.getOrDefault(event.getCurrency(), DEFAULT_EXCHANGE_RATE);
        double amountUsd = event.getAmount() * exchangeRate;
        String riskScore = calculateRiskScore(amountUsd);

        log.debug("Enriching transaction | transactionId={} | currency={} | amount={} | exchangeRate={} | amountUsd={} | riskScore={}",
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

    private static String calculateRiskScore(double amountUsd) {
        if (amountUsd < 1_000) {
            return "LOW";
        } else if (amountUsd < 10_000) {
            return "MEDIUM";
        } else {
            return "HIGH";
        }
    }
}
