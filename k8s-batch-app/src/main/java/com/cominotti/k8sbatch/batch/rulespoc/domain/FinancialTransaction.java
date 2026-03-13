// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.domain;

import java.math.BigDecimal;

/**
 * Input record representing a raw financial transaction from CSV.
 *
 * <p>Column order matches the CSV header:
 * {@code transactionId, accountId, amount, currency, timestamp}.
 *
 * @param transactionId unique transaction identifier (natural key for upsert)
 * @param accountId     account that originated the transaction
 * @param amount        transaction amount in the original currency
 * @param currency      ISO 4217 currency code (e.g. USD, EUR, GBP)
 * @param timestamp     transaction timestamp as ISO-8601 string
 */
public record FinancialTransaction(
        String transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        String timestamp
) {
}
