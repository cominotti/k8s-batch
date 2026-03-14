// SPDX-License-Identifier: Apache-2.0

package com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.dmn;

import com.cominotti.k8sbatch.batch.rulespoc.adapters.evaluatingrules.TransactionFact;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichedFinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.EnrichmentRuleConstants;
import com.cominotti.k8sbatch.batch.rulespoc.domain.FinancialTransaction;
import com.cominotti.k8sbatch.batch.rulespoc.domain.RiskScore;
import com.cominotti.k8sbatch.batch.rulespoc.domain.TransactionRulesEvaluator;
import org.kie.api.KieServices;
import org.kie.api.builder.KieBuilder;
import org.kie.api.builder.KieFileSystem;
import org.kie.api.builder.Message;
import org.kie.api.runtime.KieContainer;
import org.kie.api.runtime.KieRuntimeFactory;
import org.kie.dmn.api.core.DMNContext;
import org.kie.dmn.api.core.DMNDecisionResult;
import org.kie.dmn.api.core.DMNModel;
import org.kie.dmn.api.core.DMNResult;
import org.kie.dmn.api.core.DMNRuntime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Hybrid Java + DMN implementation of {@link TransactionRulesEvaluator} that combines
 * programmatic enrichment with DMN decision tables via the KIE DMN runtime.
 *
 * <p>The enrichment flow is split across two phases:
 * <ol>
 *   <li><strong>Java (sequential)</strong>: exchange rate lookup via
 *       {@link EnrichmentRuleConstants#rateFor(String)} and USD conversion (multiplication).
 *       These are simple sequential computations that don't benefit from a rules engine.</li>
 *   <li><strong>DMN Decision Tables</strong> ({@code risk-assessment.dmn}): risk classification
 *       and compliance flagging. The DMN engine evaluates both decisions in dependency order
 *       (Risk Score first, then Compliance Review which depends on it).</li>
 * </ol>
 *
 * <p>This demonstrates the architectural principle: use DMN for tabular business decisions that
 * benefit from visual editability and standardized notation, and Java/domain methods for
 * straightforward computations.
 *
 * <p>This adapter coexists with the classic Drools and EVRete adapters, selected via
 * {@code batch.rules.engine=dmn}.
 */
@Component
@ConditionalOnProperty(name = "batch.rules.engine", havingValue = "dmn")
public class DmnTransactionRulesEvaluator implements TransactionRulesEvaluator {

    private static final Logger log = LoggerFactory.getLogger(DmnTransactionRulesEvaluator.class);
    private static final String DMN_PATH = "dmn/risk-assessment.dmn";
    private static final String DMN_NAMESPACE = "https://cominotti.com/k8s-batch/rules-poc/risk-assessment";
    private static final String DMN_MODEL_NAME = "Risk Assessment";

    private final EnrichmentRuleConstants ruleConstants;
    private final DMNRuntime dmnRuntime;
    private final DMNModel riskAssessmentModel;

    /**
     * Initializes with {@link EnrichmentRuleConstants#DEFAULTS default} business rule constants
     * and loads the DMN model from the classpath.
     *
     * @throws IllegalStateException if the DMN model contains compilation errors
     */
    public DmnTransactionRulesEvaluator() {
        this(EnrichmentRuleConstants.DEFAULTS);
    }

    /**
     * Initializes with explicit rule constants (used for testing with custom thresholds) and loads
     * the DMN model from the classpath.
     *
     * @param ruleConstants shared business rule constants for exchange rate lookup
     * @throws IllegalStateException if the DMN model contains compilation errors
     */
    DmnTransactionRulesEvaluator(EnrichmentRuleConstants ruleConstants) {
        log.info("Initializing DMN rules evaluator | dmn={}", DMN_PATH);

        this.ruleConstants = ruleConstants;

        KieServices kieServices = KieServices.Factory.get();
        KieFileSystem kieFileSystem = kieServices.newKieFileSystem();
        kieFileSystem.write("src/main/resources/" + DMN_PATH,
                kieServices.getResources().newClassPathResource(DMN_PATH));

        KieBuilder kieBuilder = kieServices.newKieBuilder(kieFileSystem);
        kieBuilder.buildAll();

        if (kieBuilder.getResults().hasMessages(Message.Level.ERROR)) {
            throw new IllegalStateException(
                    "DMN model compilation errors: " + kieBuilder.getResults().toString());
        }

        KieContainer kieContainer = kieServices.newKieContainer(
                kieServices.getRepository().getDefaultReleaseId());

        this.dmnRuntime = KieRuntimeFactory.of(kieContainer.getKieBase()).get(DMNRuntime.class);

        this.riskAssessmentModel = dmnRuntime.getModel(DMN_NAMESPACE, DMN_MODEL_NAME);
        if (riskAssessmentModel == null) {
            throw new IllegalStateException("DMN model not found | namespace=" + DMN_NAMESPACE
                    + " | name=" + DMN_MODEL_NAME);
        }

        log.info("DMN rules evaluator initialized | dmnModel={} | dmnNamespace={}",
                riskAssessmentModel.getName(), riskAssessmentModel.getNamespace());
    }

    @Override
    public EnrichedFinancialTransaction evaluate(FinancialTransaction transaction) {
        TransactionFact fact = TransactionFact.from(transaction);

        // Phase 1: Java — exchange rate lookup + USD conversion (sequential, trivial)
        fact.setExchangeRate(ruleConstants.rateFor(fact.getCurrency()));
        fact.setAmountUsd(fact.getAmount().multiply(fact.getExchangeRate()));

        // Phase 2: DMN — risk scoring + compliance flagging (tabular decisions)
        DMNContext ctx = dmnRuntime.newContext();
        ctx.set("amountUsd", fact.getAmountUsd());
        DMNResult result = dmnRuntime.evaluateAll(riskAssessmentModel, ctx);

        if (result.hasErrors()) {
            throw new IllegalStateException("DMN evaluation errors for transaction "
                    + transaction.transactionId() + ": " + result.getMessages());
        }

        DMNDecisionResult riskResult = result.getDecisionResultByName("Risk Score");
        DMNDecisionResult complianceResult = result.getDecisionResultByName("Compliance Review");

        fact.setRiskScore(RiskScore.valueOf((String) riskResult.getResult()));
        fact.setComplianceFlag((Boolean) complianceResult.getResult());

        return fact.toEnrichedTransaction(engineName(), Instant.now());
    }

    @Override
    public String engineName() {
        return "dmn";
    }
}
