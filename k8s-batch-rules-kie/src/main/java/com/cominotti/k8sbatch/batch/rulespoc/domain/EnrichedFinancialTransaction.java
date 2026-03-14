// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.domain;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Output record produced by the rules engine after enriching a {@link FinancialTransaction}.
 *
 * <p>Carries the original transaction fields plus computed enrichment values: exchange rate,
 * USD-equivalent amount, risk score, and compliance flag. The {@code rulesEngine} field records
 * which engine (Drools or EVRete) produced this result, enabling side-by-side comparison queries.
 *
 * @param transactionId     original transaction identifier (primary key)
 * @param accountId         originating account
 * @param amount            original amount in source currency
 * @param currency          ISO 4217 currency code
 * @param exchangeRate      currency-to-USD conversion rate applied
 * @param amountUsd         amount converted to USD ({@code amount × exchangeRate})
 * @param riskScore         risk tier assigned based on USD amount thresholds
 * @param complianceFlag    {@code true} if the transaction requires compliance review
 * @param rulesEngine       name of the rules engine that produced this enrichment
 * @param originalTimestamp raw timestamp from the source transaction
 * @param processedAt       instant when enrichment was performed
 */
public record EnrichedFinancialTransaction(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        BigDecimal exchangeRate,
        BigDecimal amountUsd,
        RiskScore riskScore,
        boolean complianceFlag,
        String rulesEngine,
        String originalTimestamp,
        Instant processedAt
) {
}
