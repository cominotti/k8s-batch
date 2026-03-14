// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import org.springframework.test.context.TestPropertySource;

/**
 * Integration test for the rules engine PoC using Drools rule units.
 *
 * <p>Validates that the rule-unit adapter produces identical enrichment results to the Drools,
 * EVRete, and DMN adapters: exchange rate lookup and USD conversion via Java, then risk scoring
 * and compliance flagging via a rule unit DRL. Any divergence indicates a rule implementation
 * mismatch.
 */
@TestPropertySource(properties = "batch.rules.engine=drools-ruleunit")
class RulesEngineDroolsRuleUnitIT extends AbstractRulesEngineIT {

    @Override
    protected String expectedEngineName() {
        return "drools-ruleunit";
    }
}
