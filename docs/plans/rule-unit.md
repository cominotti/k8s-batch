## Rule-Unit Migration Plan Without a Plugin Fork

### Summary

- Keep the current Spring Boot 4.0.3 batch app as the host and adapt the rules PoC to a Kogito-style rule-unit model in-process.
- Do not bring in Kogito Spring Boot starters or make kie-maven-plugin a blocker for the first delivery.
- Use the rule-unit pattern from the Kogito example, but execute it through Drools runtime rule-unit APIs inside the existing evaluator port.
- Treat Java 21+ support for kie-maven-plugin as a separate follow-up track, not as the critical path for this PoC.

Why this is the best fit:

- The repo is already on Java 21 and Spring Boot 4.0.3 in pom.xml.
- Released Kogito 10.1.0 is aligned to Spring Boot 3.4.3, and current Kogito main is still on Spring Boot 3.5.x, so full Kogito Spring integration is a poor fit for this app.
- Released kie-memory-compiler 10.1.0 still normalizes Java 21 to 11, which explains the kie-maven-plugin blocker on the released line.
- Drools main already has explicit Java 21 coverage for kie-maven-plugin, ECJ, and memory compiler, so a fork is only justified if we later require a separate KJAR/codegen build.

### Implementation Changes

- Keep TransactionRulesEvaluator as the public integration seam and keep RulesEngineProcessor and RulesEnginePocJobConfig unchanged at the batch boundary.
- Move the mutable working object out of adapter space by renaming TransactionFact to TransactionEvaluation in the domain package.
- Add TransactionEnrichmentUnit implements RuleUnitData with:
    - DataStore<TransactionEvaluation> transactions
    - EnrichmentRuleConstants ruleConstants
- Add a new DRL resource using unit TransactionEnrichmentUnit; and express only the business decisions there.
- Keep exchange-rate lookup and USD conversion in Java before the unit fires; keep risk-score and compliance decisioning inside the rule unit. This preserves the current evaluator contract and avoids turning the batch flow into a query-first REST shape.
- Add a new engine value batch.rules.engine=drools-ruleunit; keep the existing kogito DMN adapter untouched for now so we do not silently change semantics.
- Implement a new evaluator that:
    - creates TransactionEvaluation from FinancialTransaction
    - precomputes FX and USD values using EnrichmentRuleConstants
    - inserts one item into the unit’s DataStore
    - runs RuleUnitProvider.get().createRuleUnitInstance(unitData).fire()
    - maps the mutated state back to EnrichedFinancialTransaction
- Add Drools rule-unit runtime dependencies only; do not import Kogito BOMs or Spring starters into the app module.

### Plugin Strategy

- Do not fork kogito-maven-plugin for this delivery.
- Do not fork kie-maven-plugin unless you explicitly need a separate KJAR or compile-time-generated rule-unit artifact.
- If that later becomes mandatory, fork the KIE build stack only, under an internal groupId, with a minimal backport from Drools main:
    - kie-memory-compiler Java version normalization for 20/21 and modern version strings
    - ECJ bump to the newer tested line
    - Java 21 tests for drools-ecj, kie-memory-compiler, and kie-maven-plugin
    - kie-maven-plugin support for maven.compiler.release
- Keep that fork isolated from the app repo’s first rule-unit migration so the PoC can ship independently.

### Test Plan

- Add evaluator-level tests for:
    - known-currency FX conversion
    - unknown-currency default FX
    - LOW / MEDIUM / HIGH threshold boundaries
    - compliance flag at and below the threshold
- Add a rule-unit smoke test that proves the DRL is discovered from classpath and RuleUnitProvider can fire it in the packaged app.
- Extend the existing rules PoC integration coverage to run the batch job with batch.rules.engine=drools-ruleunit against the existing CSV fixture and DB writer path.
- Run the Maven build and targeted tests on Java 21+; do not rely on Java 21 syntax inside DRL consequences in v1.

### Assumptions

- We are optimizing for the current Boot 4 app, not for generated REST/OpenAPI endpoints.
- The first delivery only needs batch-side rule-unit execution, not a reusable external KJAR.
- Preserving current batch contracts is more important than mirroring the Kogito example’s generated query endpoints.
- If generated endpoints are later desired, that should be a separate module or sidecar, not part of the first in-app migration.