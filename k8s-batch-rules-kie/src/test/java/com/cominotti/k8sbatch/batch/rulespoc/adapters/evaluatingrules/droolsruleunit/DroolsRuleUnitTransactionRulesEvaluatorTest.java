// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.droolsruleunit;

import com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.AbstractTransactionRulesEvaluatorTest;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;

/**
 * Unit tests for the Drools rule-unit-based {@link DroolsRuleUnitTransactionRulesEvaluator}.
 *
 * <p>Inherits all shared evaluator tests from {@link AbstractTransactionRulesEvaluatorTest},
 * verifying that the rule unit DRL produces identical results to the DMN adapter.
 */
class DroolsRuleUnitTransactionRulesEvaluatorTest extends AbstractTransactionRulesEvaluatorTest {

    @Override
    protected TransactionRulesEvaluator createEvaluator() {
        return new DroolsRuleUnitTransactionRulesEvaluator(EnrichmentRuleConstants.DEFAULTS);
    }

    @Override
    protected String expectedEngineName() {
        return "drools-ruleunit";
    }
}
