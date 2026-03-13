// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules;

import java.math.BigDecimal;

/**
 * Mutable fact object inserted into rules engine sessions for in-place enrichment.
 *
 * <p>Rules engines (Drools DRL, EVRete) modify this object's fields during rule evaluation.
 * After rules fire, the evaluator reads the enriched fields to build an immutable
 * {@link com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction
 * EnrichedFinancialTransaction}. This class is intentionally mutable — immutable records cannot
 * be used as Drools facts because DRL {@code then} blocks require setter access.
 */
public class TransactionFact {

    private String transactionId;
    private String accountId;
    private BigDecimal amount;
    private String currency;
    private String timestamp;

    // Enrichment fields — set by rules
    private BigDecimal exchangeRate;
    private BigDecimal amountUsd;
    private String riskScore;
    private boolean complianceFlag;

    /**
     * Creates a fact from raw transaction data with enrichment fields unset.
     *
     * @param transactionId unique transaction identifier
     * @param accountId     originating account
     * @param amount        transaction amount in source currency
     * @param currency      ISO 4217 currency code
     * @param timestamp     transaction timestamp
     */
    public TransactionFact(String transactionId, String accountId, BigDecimal amount,
                           String currency, String timestamp) {
        this.transactionId = transactionId;
        this.accountId = accountId;
        this.amount = amount;
        this.currency = currency;
        this.timestamp = timestamp;
    }

    /**
     * Returns the unique transaction identifier.
     *
     * @return transaction ID
     */
    public String getTransactionId() {
        return transactionId;
    }

    /**
     * Returns the originating account identifier.
     *
     * @return account ID
     */
    public String getAccountId() {
        return accountId;
    }

    /**
     * Returns the transaction amount in the original currency.
     *
     * @return amount in source currency
     */
    public BigDecimal getAmount() {
        return amount;
    }

    /**
     * Returns the ISO 4217 currency code.
     *
     * @return currency code
     */
    public String getCurrency() {
        return currency;
    }

    /**
     * Returns the transaction timestamp as an ISO-8601 string.
     *
     * @return timestamp string
     */
    public String getTimestamp() {
        return timestamp;
    }

    /**
     * Returns the exchange rate applied to convert to USD.
     *
     * @return currency-to-USD exchange rate
     */
    public BigDecimal getExchangeRate() {
        return exchangeRate;
    }

    /**
     * Sets the exchange rate for currency-to-USD conversion.
     *
     * @param exchangeRate the rate to apply
     */
    public void setExchangeRate(BigDecimal exchangeRate) {
        this.exchangeRate = exchangeRate;
    }

    /**
     * Returns the USD-equivalent amount.
     *
     * @return amount in USD
     */
    public BigDecimal getAmountUsd() {
        return amountUsd;
    }

    /**
     * Sets the USD-equivalent amount ({@code amount × exchangeRate}).
     *
     * @param amountUsd computed USD amount
     */
    public void setAmountUsd(BigDecimal amountUsd) {
        this.amountUsd = amountUsd;
    }

    /**
     * Returns the risk score tier.
     *
     * @return risk score: LOW, MEDIUM, or HIGH
     */
    public String getRiskScore() {
        return riskScore;
    }

    /**
     * Sets the risk score tier based on USD amount thresholds.
     *
     * @param riskScore the computed risk tier
     */
    public void setRiskScore(String riskScore) {
        this.riskScore = riskScore;
    }

    /**
     * Returns whether the transaction is flagged for compliance review.
     *
     * @return {@code true} if compliance review is required
     */
    public boolean isComplianceFlag() {
        return complianceFlag;
    }

    /**
     * Sets the compliance flag.
     *
     * @param complianceFlag {@code true} if the transaction requires compliance review
     */
    public void setComplianceFlag(boolean complianceFlag) {
        this.complianceFlag = complianceFlag;
    }
}
