// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.domain;

/**
 * Risk tier assigned to a financial transaction based on its USD-equivalent amount.
 *
 * <p>Thresholds: LOW (&lt;1000 USD), MEDIUM (&lt;10000 USD), HIGH (&ge;10000 USD).
 * Using an enum instead of raw strings provides compile-time type safety and eliminates
 * the risk of typos or invalid values propagating through the pipeline.
 */
public enum RiskScore {
    /** Transaction amount below 1000 USD. */
    LOW,
    /** Transaction amount between 1000 and 9999.99 USD. */
    MEDIUM,
    /** Transaction amount at or above 10000 USD. */
    HIGH
}
