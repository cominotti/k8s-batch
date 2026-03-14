// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules;

import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.RiskScore;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link TransactionFact} factory method and conversion to enriched domain record.
 */
class TransactionFactTest {

    private static final FinancialTransaction SAMPLE = new FinancialTransaction(
            "TXN-001", "ACCT-100", new BigDecimal("5000"), "EUR", "2024-01-15T10:30:00Z");

    @Test
    void from_mapsAllFieldsFromFinancialTransaction() {
        TransactionFact fact = TransactionFact.from(SAMPLE);

        assertThat(fact.getTransactionId()).isEqualTo("TXN-001");
        assertThat(fact.getAccountId()).isEqualTo("ACCT-100");
        assertThat(fact.getAmount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(fact.getCurrency()).isEqualTo("EUR");
        assertThat(fact.getTimestamp()).isEqualTo("2024-01-15T10:30:00Z");
    }

    @Test
    void from_enrichmentFieldsDefaultToNullAndFalse() {
        TransactionFact fact = TransactionFact.from(SAMPLE);

        assertThat(fact.getExchangeRate()).isNull();
        assertThat(fact.getAmountUsd()).isNull();
        assertThat(fact.getRiskScore()).isNull();
        assertThat(fact.isComplianceFlag()).isFalse();
    }

    @Test
    void toEnrichedTransaction_mapsAllFields() {
        TransactionFact fact = TransactionFact.from(SAMPLE);
        fact.setExchangeRate(new BigDecimal("1.08"));
        fact.setAmountUsd(new BigDecimal("5400"));
        fact.setRiskScore(RiskScore.MEDIUM);
        fact.setComplianceFlag(false);

        Instant processedAt = Instant.parse("2024-01-15T10:31:00Z");
        EnrichedFinancialTransaction result = fact.toEnrichedTransaction("test-engine", processedAt);

        assertThat(result.transactionId()).isEqualTo("TXN-001");
        assertThat(result.accountId()).isEqualTo("ACCT-100");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(result.currency()).isEqualTo("EUR");
        assertThat(result.originalTimestamp()).isEqualTo("2024-01-15T10:30:00Z");
        assertThat(result.exchangeRate()).isEqualByComparingTo(new BigDecimal("1.08"));
        assertThat(result.amountUsd()).isEqualByComparingTo(new BigDecimal("5400"));
        assertThat(result.riskScore()).isEqualTo(RiskScore.MEDIUM);
        assertThat(result.complianceFlag()).isFalse();
        assertThat(result.rulesEngine()).isEqualTo("test-engine");
        assertThat(result.processedAt()).isEqualTo(processedAt);
    }

    @Test
    void toEnrichedTransaction_worksWithNullEnrichmentFields() {
        TransactionFact fact = TransactionFact.from(SAMPLE);
        Instant processedAt = Instant.now();

        EnrichedFinancialTransaction result = fact.toEnrichedTransaction("test", processedAt);

        assertThat(result.exchangeRate()).isNull();
        assertThat(result.amountUsd()).isNull();
        assertThat(result.riskScore()).isNull();
        assertThat(result.complianceFlag()).isFalse();
    }

}
