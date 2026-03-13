// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import com.cominotti.k8sbatch.it.AbstractStandaloneBatchTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration test for the rules engine PoC using EVRete.
 *
 * <p>Validates that the EVRete Java-defined rules produce identical enrichment results to Drools:
 * same exchange rates, USD conversion, risk scoring, and compliance flagging. Any divergence
 * between this test and {@link RulesEngineDroolsIT} indicates a rule implementation mismatch.
 */
@TestPropertySource(properties = "batch.rules.engine=evrete")
class RulesEngineEvreteIT extends AbstractStandaloneBatchTest {

    @Autowired
    @Qualifier("rulesEngineJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    @Test
    void shouldCompleteJobAndEnrichAllTransactions() throws Exception {
        String inputFile = testResourcePath("test-data/csv/rules-poc/financial-transactions.csv");

        JobExecution execution = jobOperatorTestUtils.startJob(rulesEngineJobParams(inputFile));

        assertThat(execution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rules_poc_enriched_transactions", Integer.class);
        assertThat(count).isEqualTo(10);
    }

    @Test
    void shouldApplyCorrectExchangeRates() throws Exception {
        String inputFile = testResourcePath("test-data/csv/rules-poc/financial-transactions.csv");
        jobOperatorTestUtils.startJob(rulesEngineJobParams(inputFile));

        // EUR 5000 * 1.08 = 5400.00 USD
        Map<String, Object> eurRow = jdbcTemplate.queryForMap(
                "SELECT exchange_rate, amount_usd FROM rules_poc_enriched_transactions WHERE transaction_id = 'TXN002'");
        assertThat(new BigDecimal(eurRow.get("exchange_rate").toString())).isEqualByComparingTo(new BigDecimal("1.08"));
        assertThat(new BigDecimal(eurRow.get("amount_usd").toString())).isEqualByComparingTo(new BigDecimal("5400.00"));

        // GBP 15000 * 1.27 = 19050.00 USD
        Map<String, Object> gbpRow = jdbcTemplate.queryForMap(
                "SELECT exchange_rate, amount_usd FROM rules_poc_enriched_transactions WHERE transaction_id = 'TXN003'");
        assertThat(new BigDecimal(gbpRow.get("exchange_rate").toString())).isEqualByComparingTo(new BigDecimal("1.27"));
        assertThat(new BigDecimal(gbpRow.get("amount_usd").toString())).isEqualByComparingTo(new BigDecimal("19050.00"));

        // JPY 200000 * 0.0067 = 1340.00 USD
        Map<String, Object> jpyRow = jdbcTemplate.queryForMap(
                "SELECT exchange_rate, amount_usd FROM rules_poc_enriched_transactions WHERE transaction_id = 'TXN004'");
        assertThat(new BigDecimal(jpyRow.get("exchange_rate").toString())).isEqualByComparingTo(new BigDecimal("0.0067"));
        assertThat(new BigDecimal(jpyRow.get("amount_usd").toString())).isEqualByComparingTo(new BigDecimal("1340.00"));
    }

    @Test
    void shouldAssignCorrectRiskScores() throws Exception {
        String inputFile = testResourcePath("test-data/csv/rules-poc/financial-transactions.csv");
        jobOperatorTestUtils.startJob(rulesEngineJobParams(inputFile));

        // USD 500.00 → LOW
        assertRiskScore("TXN001", "LOW");
        // USD 999.99 → LOW (boundary)
        assertRiskScore("TXN006", "LOW");
        // USD 1000.00 → MEDIUM (boundary)
        assertRiskScore("TXN007", "MEDIUM");
        // EUR 5000 → 5400 USD → MEDIUM
        assertRiskScore("TXN002", "MEDIUM");
        // GBP 15000 → 19050 USD → HIGH
        assertRiskScore("TXN003", "HIGH");
        // GBP 100000 → 127000 USD → HIGH
        assertRiskScore("TXN009", "HIGH");
    }

    @Test
    void shouldSetComplianceFlags() throws Exception {
        String inputFile = testResourcePath("test-data/csv/rules-poc/financial-transactions.csv");
        jobOperatorTestUtils.startJob(rulesEngineJobParams(inputFile));

        // LOW risk → no flag
        assertComplianceFlag("TXN001", false);
        // MEDIUM risk → no flag
        assertComplianceFlag("TXN002", false);
        // HIGH risk → flagged
        assertComplianceFlag("TXN003", true);
        // GBP 100000 → 127000 USD → HIGH + ≥50K → flagged
        assertComplianceFlag("TXN009", true);
    }

    @Test
    void shouldRecordEvreteAsEngineForAllRows() throws Exception {
        String inputFile = testResourcePath("test-data/csv/rules-poc/financial-transactions.csv");
        jobOperatorTestUtils.startJob(rulesEngineJobParams(inputFile));

        Integer evreteCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rules_poc_enriched_transactions WHERE rules_engine = 'evrete'",
                Integer.class);
        assertThat(evreteCount).isEqualTo(10);
    }

    private void assertRiskScore(String transactionId, String expectedScore) {
        String score = jdbcTemplate.queryForObject(
                "SELECT risk_score FROM rules_poc_enriched_transactions WHERE transaction_id = ?",
                String.class, transactionId);
        assertThat(score).as("Risk score for %s", transactionId).isEqualTo(expectedScore);
    }

    private void assertComplianceFlag(String transactionId, boolean expected) {
        Boolean flag = jdbcTemplate.queryForObject(
                "SELECT compliance_flag FROM rules_poc_enriched_transactions WHERE transaction_id = ?",
                Boolean.class, transactionId);
        assertThat(flag).as("Compliance flag for %s", transactionId).isEqualTo(expected);
    }
}
