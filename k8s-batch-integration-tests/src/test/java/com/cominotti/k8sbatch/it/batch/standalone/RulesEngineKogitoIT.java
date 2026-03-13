// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.it.batch.standalone;

import org.springframework.test.context.TestPropertySource;

/**
 * Integration test for the rules engine PoC using Kogito (Java + DMN hybrid).
 *
 * <p>Validates that the hybrid Kogito adapter produces identical enrichment results to the Drools
 * and EVRete adapters: exchange rate lookup via Java, then risk scoring and compliance flagging
 * via DMN decision tables. Any divergence indicates a rule implementation mismatch.
 */
@TestPropertySource(properties = "batch.rules.engine=kogito")
class RulesEngineKogitoIT extends AbstractRulesEngineIT {

    @Override
    protected String expectedEngineName() {
        return "kogito";
    }
}
