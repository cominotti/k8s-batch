// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules;

import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.RiskScore;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared unit tests for {@link TransactionRulesEvaluator} implementations in the rules-kie module.
 *
 * <p>Both KIE evaluators (DMN and Drools Rule Units) must produce identical enrichment results for
 * the same inputs. Subclasses provide only a factory method and expected engine name — any test
 * divergence indicates a rule implementation mismatch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractTransactionRulesEvaluatorTest {

    private TransactionRulesEvaluator evaluator;

    protected abstract TransactionRulesEvaluator createEvaluator();

    protected abstract String expectedEngineName();

    @BeforeAll
    void initEvaluator() {
        evaluator = createEvaluator();
    }

    private static FinancialTransaction txn(String id, BigDecimal amount, String currency) {
        return new FinancialTransaction(id, "ACCT-001", amount, currency, "2024-01-15T10:30:00Z");
    }

    // ── Exchange rate scenarios ──────────────────────────────────────────────────

    @Test
    void evaluate_usdTransaction_appliesUsdRate() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("USD-1", new BigDecimal("500"), "USD"));

        assertThat(result.exchangeRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(result.amountUsd()).isEqualByComparingTo(new BigDecimal("500"));
    }

    @Test
    void evaluate_eurTransaction_appliesEurRate() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("EUR-1", new BigDecimal("5000"), "EUR"));

        assertThat(result.exchangeRate()).isEqualByComparingTo(new BigDecimal("1.08"));
        assertThat(result.amountUsd()).isEqualByComparingTo(new BigDecimal("5400"));
    }

    @Test
    void evaluate_gbpTransaction_appliesGbpRate() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("GBP-1", new BigDecimal("15000"), "GBP"));

        assertThat(result.exchangeRate()).isEqualByComparingTo(new BigDecimal("1.27"));
        assertThat(result.amountUsd()).isEqualByComparingTo(new BigDecimal("19050"));
    }

    @Test
    void evaluate_jpyTransaction_appliesJpyRate() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("JPY-1", new BigDecimal("200000"), "JPY"));

        assertThat(result.exchangeRate()).isEqualByComparingTo(new BigDecimal("0.0067"));
        assertThat(result.amountUsd()).isEqualByComparingTo(new BigDecimal("1340"));
    }

    @Test
    void evaluate_brlTransaction_appliesBrlRate() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("BRL-1", new BigDecimal("3000"), "BRL"));

        assertThat(result.exchangeRate()).isEqualByComparingTo(new BigDecimal("0.18"));
        assertThat(result.amountUsd()).isEqualByComparingTo(new BigDecimal("540"));
    }

    @Test
    void evaluate_unknownCurrency_appliesDefaultRate() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("CHF-1", new BigDecimal("1000"), "CHF"));

        assertThat(result.exchangeRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(result.amountUsd()).isEqualByComparingTo(new BigDecimal("1000"));
    }

    // ── Risk score boundary scenarios ───────────────────────────────────────────

    @Test
    void evaluate_amountUsdBelowMediumThreshold_assignsLow() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("LOW-1", new BigDecimal("999.99"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.LOW);
    }

    @Test
    void evaluate_amountUsdAtMediumThreshold_assignsMedium() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("MED-1", new BigDecimal("1000"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.MEDIUM);
    }

    @Test
    void evaluate_amountUsdBetweenThresholds_assignsMedium() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("MED-2", new BigDecimal("5000"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.MEDIUM);
    }

    @Test
    void evaluate_amountUsdJustBelowHighThreshold_assignsMedium() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("MED-3", new BigDecimal("9999.99"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.MEDIUM);
    }

    @Test
    void evaluate_amountUsdAtHighThreshold_assignsHigh() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("HIGH-1", new BigDecimal("10000"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.HIGH);
    }

    @Test
    void evaluate_amountUsdAboveHighThreshold_assignsHigh() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("HIGH-2", new BigDecimal("50000"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.HIGH);
    }

    // ── Compliance flag scenarios ────────────────────────────────────────────────

    @Test
    void evaluate_highRiskTransaction_flagsCompliance() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("COMP-1", new BigDecimal("10000"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.HIGH);
        assertThat(result.complianceFlag()).isTrue();
    }

    @Test
    void evaluate_highRiskAboveComplianceThreshold_flagsCompliance() {
        // EUR 50000 * 1.08 = 54000 USD → HIGH risk (>= 10000) and above compliance threshold (>= 50000)
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("COMP-2", new BigDecimal("50000"), "EUR"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.HIGH);
        assertThat(result.complianceFlag()).isTrue();
    }

    @Test
    void evaluate_mediumRiskBelowComplianceThreshold_doesNotFlagCompliance() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("COMP-3", new BigDecimal("5000"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.MEDIUM);
        assertThat(result.complianceFlag()).isFalse();
    }

    @Test
    void evaluate_lowRiskTransaction_doesNotFlagCompliance() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("COMP-4", new BigDecimal("500"), "USD"));

        assertThat(result.riskScore()).isEqualTo(RiskScore.LOW);
        assertThat(result.complianceFlag()).isFalse();
    }

    // ── Output integrity ────────────────────────────────────────────────────────

    @Test
    void evaluate_preservesOriginalTransactionFields() {
        FinancialTransaction input = txn("ORIG-1", new BigDecimal("1234.56"), "GBP");
        EnrichedFinancialTransaction result = evaluator.evaluate(input);

        assertThat(result.transactionId()).isEqualTo("ORIG-1");
        assertThat(result.accountId()).isEqualTo("ACCT-001");
        assertThat(result.amount()).isEqualByComparingTo(new BigDecimal("1234.56"));
        assertThat(result.currency()).isEqualTo("GBP");
        assertThat(result.originalTimestamp()).isEqualTo("2024-01-15T10:30:00Z");
    }

    @Test
    void evaluate_setsEngineNameOnResult() {
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("ENG-1", new BigDecimal("100"), "USD"));

        assertThat(result.rulesEngine()).isEqualTo(expectedEngineName());
    }

    @Test
    void evaluate_setsProcessedAtTimestamp() {
        Instant before = Instant.now();
        EnrichedFinancialTransaction result = evaluator.evaluate(
                txn("TIME-1", new BigDecimal("100"), "USD"));
        Instant after = Instant.now();

        assertThat(result.processedAt()).isBetween(before, after);
    }
}
