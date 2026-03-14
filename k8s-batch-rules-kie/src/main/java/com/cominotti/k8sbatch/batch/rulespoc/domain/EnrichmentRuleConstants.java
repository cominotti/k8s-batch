// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.domain;

import java.math.BigDecimal;
import java.util.Map;

/**
 * Shared business rule constants for financial transaction enrichment.
 *
 * <p>All rules engine adapters consume these constants to ensure consistent rule evaluation.
 * Exchange rates are static for deterministic test predictability — in production, this would
 * be replaced with an external FX rate service.
 *
 * @param exchangeRates       currency code → USD conversion rate (e.g. EUR=1.08, GBP=1.27)
 * @param defaultExchangeRate fallback rate for unknown currencies
 * @param mediumThreshold     USD amount threshold for MEDIUM risk (inclusive lower bound)
 * @param highThreshold       USD amount threshold for HIGH risk (inclusive lower bound)
 * @param complianceThreshold USD amount threshold that triggers compliance review
 */
public record EnrichmentRuleConstants(
        Map<String, BigDecimal> exchangeRates,
        BigDecimal defaultExchangeRate,
        BigDecimal mediumThreshold,
        BigDecimal highThreshold,
        BigDecimal complianceThreshold
) {

    /** Default constants matching the existing business rules. */
    public static final EnrichmentRuleConstants DEFAULTS = new EnrichmentRuleConstants(
            Map.of(
                    "USD", new BigDecimal("1.0"),
                    "EUR", new BigDecimal("1.08"),
                    "GBP", new BigDecimal("1.27"),
                    "JPY", new BigDecimal("0.0067"),
                    "BRL", new BigDecimal("0.18")
            ),
            new BigDecimal("1.0"),
            new BigDecimal("1000"),
            new BigDecimal("10000"),
            new BigDecimal("50000")
    );

    /**
     * Returns the exchange rate for the given currency, falling back to the default rate.
     *
     * @param currencyCode ISO 4217 currency code
     * @return exchange rate to USD
     */
    public BigDecimal rateFor(String currencyCode) {
        return exchangeRates.getOrDefault(currencyCode, defaultExchangeRate);
    }

    /**
     * Determines the risk score for a given USD amount based on configured thresholds.
     *
     * @param amountUsd transaction amount in USD
     * @return risk tier
     */
    public RiskScore riskScoreFor(BigDecimal amountUsd) {
        if (amountUsd.compareTo(mediumThreshold) < 0) {
            return RiskScore.LOW;
        } else if (amountUsd.compareTo(highThreshold) < 0) {
            return RiskScore.MEDIUM;
        } else {
            return RiskScore.HIGH;
        }
    }

    /**
     * Determines whether a transaction requires compliance review.
     *
     * @param riskScore the transaction's risk tier
     * @param amountUsd transaction amount in USD
     * @return {@code true} if compliance review is required
     */
    public boolean requiresComplianceReview(RiskScore riskScore, BigDecimal amountUsd) {
        return riskScore == RiskScore.HIGH || amountUsd.compareTo(complianceThreshold) >= 0;
    }
}
