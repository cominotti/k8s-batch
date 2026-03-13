// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.drools;

import com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.TransactionFact;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;
import org.drools.model.codegen.ExecutableModelProject;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Drools-based implementation of {@link TransactionRulesEvaluator}.
 *
 * <p>Loads business rules from the {@code transaction-enrichment.drl} file on the classpath and
 * builds a {@link KieContainer} at startup. Each {@link #evaluate} call creates a new stateful
 * {@link KieSession}, inserts the transaction as a mutable {@link TransactionFact}, fires all
 * rules, and reads back the enriched fields.
 *
 * <p>Uses the Drools core API directly (no Spring Boot starter) to avoid version conflicts with
 * Spring Boot 4.x. The {@link KieFileSystem} builder is used instead of {@code kmodule.xml} for
 * fully programmatic configuration.
 */
@Component
@ConditionalOnProperty(name = "batch.rules.engine", havingValue = "drools")
public class DroolsTransactionRulesEvaluator implements TransactionRulesEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DroolsTransactionRulesEvaluator.class);
    private static final String DRL_PATH = "rules/transaction-enrichment.drl";

    private final KieContainer kieContainer;
    private final EnrichmentRuleConstants ruleConstants;

    /**
     * Builds the Drools {@link KieContainer} from the DRL file on the classpath, using
     * {@link EnrichmentRuleConstants#DEFAULTS default} business rule constants.
     *
     * @throws IllegalStateException if the DRL file contains compilation errors
     */
    public DroolsTransactionRulesEvaluator() {
        this(EnrichmentRuleConstants.DEFAULTS);
    }

    /**
     * Builds the Drools {@link KieContainer} with explicit rule constants (used for testing
     * with custom thresholds).
     *
     * @param ruleConstants shared business rule constants injected as a DRL global
     * @throws IllegalStateException if the DRL file contains compilation errors
     */
    DroolsTransactionRulesEvaluator(EnrichmentRuleConstants ruleConstants) {
        log.info("Initializing Drools rules evaluator | drl={}", DRL_PATH);

        this.ruleConstants = ruleConstants;

        KieServices kieServices = KieServices.Factory.get();
        KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
        kieFileSystem.write("src/main/resources/" + DRL_PATH,
                kieServices.getResources().newClassPathResource(DRL_PATH));

        KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);
        kieBuilder.buildAll(ExecutableModelProject.class);

        if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(
                    "Drools DRL compilation errors: " + kieBuilder.getResults().toString());
        }

        this.kieContainer = kieServices.newKieContainer(
                kieServices.getRepository().getDefaultReleaseId());

        log.info("Drools rules evaluator initialized successfully");
    }

    @Override
    public EnrichedFinancialTransaction evaluate(FinancialTransaction transaction) {
        TransactionFact fact = TransactionFact.from(transaction);

        KieSession session = kieContainer.newKieSession();
        try {
            session.setGlobal("ruleConstants", ruleConstants);
            session.insert(fact);
            session.fireAllRules();
        } finally {
            session.dispose();
        }

        return fact.toEnrichedTransaction(engineName(), Instant.now());
    }

    @Override
    public String engineName() {
        return "drools";
    }
}
