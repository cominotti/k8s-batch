// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.dmn;

import com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.AbstractTransactionRulesEvaluatorTest;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;

/**
 * Unit tests for the DMN-based {@link DmnTransactionRulesEvaluator}.
 *
 * <p>Inherits all shared evaluator tests from {@link AbstractTransactionRulesEvaluatorTest},
 * verifying that the {@code risk-assessment.dmn} decision tables produce identical results
 * to the Drools rule unit adapter.
 */
class DmnTransactionRulesEvaluatorTest extends AbstractTransactionRulesEvaluatorTest {

    @Override
    protected TransactionRulesEvaluator createEvaluator() {
        return new DmnTransactionRulesEvaluator(EnrichmentRuleConstants.DEFAULTS);
    }

    @Override
    protected String expectedEngineName() {
        return "dmn";
    }
}
