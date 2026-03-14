// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link EnrichmentRuleConstants} business rule logic.
 *
 * <p>Verifies exchange rate lookup, risk score threshold classification, and compliance review
 * determination — the core domain logic shared by all rules engine adapters.
 */
class EnrichmentRuleConstantsTest {

    private final EnrichmentRuleConstants constants = EnrichmentRuleConstants.DEFAULTS;

    // ── rateFor ─────────────────────────────────────────────────────────────────

    @Test
    void rateFor_usd_returnsOnePointZero() {
        assertThat(constants.rateFor("USD")).isEqualByComparingTo(new BigDecimal("1.0"));
    }

    @Test
    void rateFor_eur_returnsConfiguredRate() {
        assertThat(constants.rateFor("EUR")).isEqualByComparingTo(new BigDecimal("1.08"));
    }

    @Test
    void rateFor_gbp_returnsConfiguredRate() {
        assertThat(constants.rateFor("GBP")).isEqualByComparingTo(new BigDecimal("1.27"));
    }

    @Test
    void rateFor_jpy_returnsConfiguredRate() {
        assertThat(constants.rateFor("JPY")).isEqualByComparingTo(new BigDecimal("0.0067"));
    }

    @Test
    void rateFor_brl_returnsConfiguredRate() {
        assertThat(constants.rateFor("BRL")).isEqualByComparingTo(new BigDecimal("0.18"));
    }

    @Test
    void rateFor_unknownCurrency_returnsDefaultRate() {
        assertThat(constants.rateFor("CHF")).isEqualByComparingTo(new BigDecimal("1.0"));
    }

    // ── riskScoreFor ────────────────────────────────────────────────────────────

    @Test
    void riskScoreFor_zero_returnsLow() {
        assertThat(constants.riskScoreFor(BigDecimal.ZERO)).isEqualTo(RiskScore.LOW);
    }

    @Test
    void riskScoreFor_belowMediumThreshold_returnsLow() {
        assertThat(constants.riskScoreFor(new BigDecimal("999.99"))).isEqualTo(RiskScore.LOW);
    }

    @Test
    void riskScoreFor_atMediumThreshold_returnsMedium() {
        assertThat(constants.riskScoreFor(new BigDecimal("1000"))).isEqualTo(RiskScore.MEDIUM);
    }

    @Test
    void riskScoreFor_betweenThresholds_returnsMedium() {
        assertThat(constants.riskScoreFor(new BigDecimal("5000"))).isEqualTo(RiskScore.MEDIUM);
    }

    @Test
    void riskScoreFor_justBelowHighThreshold_returnsMedium() {
        assertThat(constants.riskScoreFor(new BigDecimal("9999.99"))).isEqualTo(RiskScore.MEDIUM);
    }

    @Test
    void riskScoreFor_atHighThreshold_returnsHigh() {
        assertThat(constants.riskScoreFor(new BigDecimal("10000"))).isEqualTo(RiskScore.HIGH);
    }

    @Test
    void riskScoreFor_aboveHighThreshold_returnsHigh() {
        assertThat(constants.riskScoreFor(new BigDecimal("50000"))).isEqualTo(RiskScore.HIGH);
    }

    // ── requiresComplianceReview ────────────────────────────────────────────────

    @Test
    void requiresComplianceReview_highRisk_returnsTrue() {
        assertThat(constants.requiresComplianceReview(RiskScore.HIGH, new BigDecimal("10000")))
                .isTrue();
    }

    @Test
    void requiresComplianceReview_mediumRiskAtComplianceThreshold_returnsTrue() {
        assertThat(constants.requiresComplianceReview(RiskScore.MEDIUM, new BigDecimal("50000")))
                .isTrue();
    }

    @Test
    void requiresComplianceReview_mediumRiskBelowComplianceThreshold_returnsFalse() {
        assertThat(constants.requiresComplianceReview(RiskScore.MEDIUM, new BigDecimal("49999.99")))
                .isFalse();
    }

    @Test
    void requiresComplianceReview_lowRiskBelowComplianceThreshold_returnsFalse() {
        assertThat(constants.requiresComplianceReview(RiskScore.LOW, new BigDecimal("500")))
                .isFalse();
    }

    // ── DEFAULTS validation ─────────────────────────────────────────────────────

    @Test
    void defaults_containsExpectedExchangeRates() {
        assertThat(constants.exchangeRates()).hasSize(5)
                .containsEntry("USD", new BigDecimal("1.0"))
                .containsEntry("EUR", new BigDecimal("1.08"))
                .containsEntry("GBP", new BigDecimal("1.27"))
                .containsEntry("JPY", new BigDecimal("0.0067"))
                .containsEntry("BRL", new BigDecimal("0.18"));
    }

    @Test
    void defaults_containsExpectedThresholds() {
        assertThat(constants.defaultExchangeRate()).isEqualByComparingTo(new BigDecimal("1.0"));
        assertThat(constants.mediumThreshold()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(constants.highThreshold()).isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(constants.complianceThreshold()).isEqualByComparingTo(new BigDecimal("50000"));
    }
}
