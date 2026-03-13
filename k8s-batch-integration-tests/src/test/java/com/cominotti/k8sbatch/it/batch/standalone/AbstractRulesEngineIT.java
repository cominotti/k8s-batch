// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import com.cominotti.k8sbatch.it.AbstractStandaloneBatchTest;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.test.JobOperatorTestUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Shared test logic for all rules engine PoC adapters (Drools, EVRete, Kogito).
 *
 * <p>Subclasses activate a specific engine via {@code @TestPropertySource} and override
 * {@link #expectedEngineName()} to verify the {@code rules_engine} column. All business
 * assertions (exchange rates, risk scores, compliance flags) are identical across engines —
 * any divergence indicates a rule implementation mismatch.
 */
abstract class AbstractRulesEngineIT extends AbstractStandaloneBatchTest {

    @Autowired
    @Qualifier("rulesEngineJobOperatorTestUtils")
    private JobOperatorTestUtils jobOperatorTestUtils;

    /**
     * Returns the expected value of the {@code rules_engine} column (e.g. "drools", "evrete",
     * "kogito").
     *
     * @return engine name matching the adapter's {@code engineName()} return value
     */
    protected abstract String expectedEngineName();

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

        assertRiskScore("TXN001", "LOW");
        assertRiskScore("TXN006", "LOW");
        assertRiskScore("TXN007", "MEDIUM");
        assertRiskScore("TXN002", "MEDIUM");
        assertRiskScore("TXN003", "HIGH");
        assertRiskScore("TXN009", "HIGH");
    }

    @Test
    void shouldSetComplianceFlags() throws Exception {
        String inputFile = testResourcePath("test-data/csv/rules-poc/financial-transactions.csv");
        jobOperatorTestUtils.startJob(rulesEngineJobParams(inputFile));

        assertComplianceFlag("TXN001", false);
        assertComplianceFlag("TXN002", false);
        assertComplianceFlag("TXN003", true);
        assertComplianceFlag("TXN009", true);
    }

    @Test
    void shouldRecordExpectedEngineForAllRows() throws Exception {
        String inputFile = testResourcePath("test-data/csv/rules-poc/financial-transactions.csv");
        jobOperatorTestUtils.startJob(rulesEngineJobParams(inputFile));

        Integer engineCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rules_poc_enriched_transactions WHERE rules_engine = ?",
                Integer.class, expectedEngineName());
        assertThat(engineCount).isEqualTo(10);
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
