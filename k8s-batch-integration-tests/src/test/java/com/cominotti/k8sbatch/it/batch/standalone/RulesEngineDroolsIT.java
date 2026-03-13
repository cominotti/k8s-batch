// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import org.springframework.test.context.TestPropertySource;

/**
 * Integration test for the rules engine PoC using Drools.
 *
 * <p>Validates that the Drools DRL rules produce correct enrichment results for financial
 * transactions: exchange rate lookup, USD conversion, risk scoring, and compliance flagging.
 */
@TestPropertySource(properties = "batch.rules.engine=drools")
class RulesEngineDroolsIT extends AbstractRulesEngineIT {

    @Override
    protected String expectedEngineName() {
        return "drools";
    }
}
