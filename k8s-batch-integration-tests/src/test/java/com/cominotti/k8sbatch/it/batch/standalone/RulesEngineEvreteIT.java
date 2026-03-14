// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import org.springframework.test.context.TestPropertySource;

/**
 * Integration test for the rules engine PoC using EVRete.
 *
 * <p>Validates that the EVRete Java-defined rules produce identical enrichment results to Drools
 * and DMN: same exchange rates, USD conversion, risk scoring, and compliance flagging. Any
 * divergence between engines indicates a rule implementation mismatch.
 */
@TestPropertySource(properties = "batch.rules.engine=evrete")
class RulesEngineEvreteIT extends AbstractRulesEngineIT {

    @Override
    protected String expectedEngineName() {
        return "evrete";
    }
}
