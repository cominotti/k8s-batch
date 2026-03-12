---
name: hex-ddd
description: "Evaluate Java code changes against Hexagonal Architecture, CQS, and DDD. Auto-invoked on Java file changes in k8s-batch-app/src/main/java. Pragmatically assesses domain model richness, port/adapter boundaries, CQS compliance, dependency direction, Java Records usage, and package structure. Use whenever Java files are created, modified, or refactored, or when the user asks about architecture, DDD compliance, or separation of concerns."
disable-model-invocation: false
---

Evaluate Java code changes against Hexagonal Architecture, Command-Query Separation (CQS), and Domain-Driven Design (DDD). The goal is to guide the codebase toward better architecture gradually — the current structure is not the target state. Be pragmatic: Spring Batch code has different needs than domain logic. Prioritize correctness, then simplicity, then testability, then maintainability.

Assume developers may not know these patterns. When using a term from the Concept Glossary (at the end of this document), include a one-line explanation the first time it appears in the report. When genuinely ambiguous tradeoffs exist, present options with a strong recommendation. When the right answer is clear, state it directly.

## Pragmatism Guardrails

These principles override pattern-matching instinct. When in doubt, favor the simpler option:

1. **An interface with one implementation is noise, not architecture.** Only recommend an interface when there are multiple implementations, or when testability demands a seam that does not exist today.
2. **Spring Batch interfaces ARE your ports.** `ItemProcessor`, `Partitioner`, `ItemReader`, `ItemWriter` are well-designed contracts. Wrapping them in custom port interfaces adds indirection without benefit unless you need to decouple from Spring Batch entirely. See the Natural Ports table in Step 1e.
3. **A static factory method is simpler than a factory interface.** Prefer static factories (e.g., `CsvRecordWriter.create()`) over `@Component` + interface unless the factory needs to be swapped.
4. **Records are the default for data-focused types.** DTOs, value objects, configuration properties, response objects — all should be `record` classes. Only use `class` when the type genuinely needs mutability (JPA entities with lifecycle state, builder patterns). See Step 1a for details.
5. **Not every String needs a value object.** Recommend value objects when: the type appears in 3+ signatures, has validation rules, or two types could be confused. Otherwise, primitives are fine. See Step 1c for the full heuristic.
6. **Domain purity matters more in growing domains.** The transaction enrichment pipeline (exchange rates, risk scoring) has more domain potential than CSV ETL (read, filter blanks, write). Calibrate scrutiny accordingly.
7. **Testability is the practical test of architecture.** If a class can be unit-tested without Spring context, its architecture is good enough — regardless of whether it formally follows hex arch naming conventions.
8. **`@StepScope` and `@JobScope` are acceptable on domain classes.** These Spring Batch annotations enable partition-specific parameter injection via `@Value("#{stepExecutionContext[...]}")`. They are framework metadata, not business logic coupling — treat them like `@ConfigurationProperties`.
9. **Every package should contain files from a single architectural zone.** If a package has files from multiple zones (e.g., controllers + `@Configuration` + DTOs), it needs sub-packages to separate them — even if the package is small today. This applies to all packages, not just bounded contexts. Driving adapter packages (REST, gRPC, CLI) are not bounded contexts but still need internal layering when they contain multiple zones.

## Adapter Port-Name Convention

Adapter packages are named after the port they serve, following the pattern `adapters/<portname>/<technology>/` from Garrido de Paz & Cockburn's "Hexagonal Architecture Explained", adapted to Java package naming (all-lowercase, no separators):

- **Port name**: gerund+noun describing the capability (e.g., `launchingjobs`, `persistingrecords`, `streamingevents`). The verb naturally encodes direction — driving verbs (launching, managing) indicate inbound adapters; driven verbs (persisting, reading, streaming) indicate outbound adapters.
- **Technology**: the implementation technology (`rest/`, `jdbc/`, `kafka/`, `file/`, `logging/`).

Each port name maps to exactly one port interface — either a natural port (Spring Batch interface) or a custom interface in `domain/` or `ports/`. This naming removes the need for generic `in/` and `out/` markers because the port name itself communicates direction.

| Port name | Direction | Technology | Maps to port |
|-----------|-----------|------------|-------------|
| `launchingjobs` | Driving | `rest/` | REST API → `JobOperator` |
| `readingcsv` | Driven | `file/` | `ItemReader<CsvRecord>` (natural) |
| `persistingrecords` | Driven | `jdbc/` | `ItemWriter<CsvRecord>` (natural) |
| `observingexecution` | Driven | `logging/` | `JobExecutionListener`, `StepExecutionListener` (natural) |
| `streamingevents` | Driven | `kafka/` | Kafka consumer/producer factories |
| `persistingtransactions` | Driven | `jdbc/` | `ItemWriter<EnrichedTransactionEvent>` (natural) |

## Driven Adapter Port Rule

Every driven adapter must correspond to a port — an interface that the domain defines or depends on. This is the core hexagonal dependency rule: the domain never imports infrastructure; it depends on a port, and the adapter provides the implementation.

**Natural ports** (preferred): Spring Batch interfaces like `ItemReader<T>`, `ItemWriter<T>`, `ItemProcessor<I,O>`, and `Partitioner` already serve as ports. Adapter factories that return objects implementing these interfaces satisfy the rule without custom interfaces. See the Natural Ports table in Step 1e.

**Custom ports** (when needed): When the domain requires a capability that no framework interface provides, define a custom interface in `domain/` or `ports/`. Examples: a `RateProvider` if exchange rates came from an external API, a `NotificationSender` for alerting on batch failures.

**When to flag**: A class in an adapter package that doesn't implement or produce an implementation of any port is either:
- Framework glue misplaced in adapters → recommend moving to `config/`
- Missing a port interface → recommend extracting one if it improves testability

Do NOT flag adapter factories that produce Spring Batch interface implementations — the factory pattern is the adapter, and the Spring Batch interface is the port.

## Target Package Structure

Feature-first with layer sub-packages — each feature package is a DDD Bounded Context. Adapter packages use the port-name convention (`adapters/<portname>/<technology>/`):

```
com.cominotti.k8sbatch/
  batch/
    transaction/               # Bounded Context: Transaction Enrichment
      domain/                  # Business rules, value objects, processors
      adapters/
        streamingevents/
          kafka/               # Kafka consumer/producer factories
        persistingtransactions/
          jdbc/                # Enriched transaction JDBC writer
      config/                  # Job/step wiring, @Configuration classes
      ports/                   # ONLY when custom interfaces needed beyond Spring Batch
    filerange/                 # Bounded Context: File-Range ETL
      domain/
      config/
    multifile/                 # Bounded Context: Multi-File ETL
      domain/
      config/
    common/                    # Shared Kernel
      domain/                  # CsvRecord, BatchStepNames, shared value objects
      adapters/
        readingcsv/
          file/                # CSV file reader factory
        persistingrecords/
          jdbc/                # CSV record JDBC writer
        observingexecution/
          logging/             # Job/step execution listeners, duration utils
  config/                      # Infrastructure (cross-cutting: remote partitioning, Kafka integration)
  web/                         # Driving Adapter Context (REST API)
    adapters/
      launchingjobs/
        rest/                  # REST controllers
    config/                    # Async job operator config
    dto/                       # REST response/request types
```

## Severity Levels

- **[FLAG]** — Architectural violation that will cause real problems (dependency direction wrong, business logic in glue, CQS violation on public API). Recommend fixing in the current change, but do not block — gradual migration is the strategy.
- **[RECOMMEND]** — Meaningful improvement. When genuinely ambiguous, includes a tradeoff matrix. Developer decides.
- **[CONSIDER]** — Minor observation. Current approach is acceptable. Brief mention, no action required.
- **[GOOD]** — Existing pattern that already follows Hex Arch/CQS/DDD well. Reinforces good habits and teaches by example. Include when code genuinely follows the patterns well — do not fabricate praise.

## Step 0: Identify Changed Files and Classify by Zone

Determine which files changed using git (try `git diff --name-only`, then `--cached`, then `HEAD~1`, then `git status --porcelain`). Filter to Java files in `k8s-batch-app/src/main/java/`. Skip deleted files, renamed-away paths, test files (`*IT.java`, `*E2E.java`, `*Test.java`), generated code (`target/generated-sources/`), and `K8sBatchApplication.java`.

**Classify each file into a zone** based on its responsibilities (not its current location — the classification is prescriptive):

| Zone | Characteristics | Scrutiny |
|------|----------------|----------|
| **Domain** | Business rules, validation, calculations, processors, partitioners, value objects, constants. No I/O. | Full |
| **Adapter (driving)** | Inbound adapter implementations in `adapters/<portname>/<tech>/`. REST controllers, gRPC services, CLI handlers. Translates external requests into domain calls. | Light |
| **Adapter (driven)** | Outbound adapter implementations in `adapters/<portname>/<tech>/`. Factories for readers/writers, Kafka config, filesystem integration. Must correspond to a port (see Driven Adapter Port Rule). | Light |
| **Config** | Orchestrates domain + adapters into jobs/steps. `@Configuration` classes that compose beans. | Moderate |
| **Framework Glue** | Spring Integration channels, remote partitioning infra, cross-cutting infrastructure. | Minimal |
| **Shared Kernel** | Code in `common/` shared across 2+ bounded contexts. Apply the scrutiny level matching its sub-zone (domain, adapter). |  |

For classes that span zones (e.g., a config class containing business logic), classify by primary responsibility and flag the zone-crossing code for extraction.

State the zone classification and reasoning at the top of each file's review.

## Step 1: Domain Zone Review (Full Scrutiny)

### 1a. Java Records Preference

Data-focused types should be Java `record` classes. Records provide immutability, compact syntax, and correct `equals`/`hashCode` — exactly what DDD value objects need.

**Flag** when a class has only `final` fields with getters and no meaningful behavior, or when a `@ConfigurationProperties` type could be a record with compact constructor.

**Do NOT flag** types that genuinely need mutability (JPA entities, builder patterns, framework-required mutable POJOs).

### 1b. Domain Model Richness

- **Rich model (good)**: Business rules live ON the entity/value object. The class validates its own invariants.
- **Anemic model (flag when behavior belongs to the model)**: If multiple places do the same validation or calculation on the same type's fields, that logic belongs on the type.
- **Do NOT flag** simple DTOs/records that are intentionally data-only (e.g., `CsvRecord`).

### 1c. Value Object Identification

**Recommend a value object when**: the type appears in 3+ method signatures, has validation rules, or represents a concept that could be confused with another primitive (e.g., `TransactionId` vs `AccountId`). Value objects should be Java `record` classes with validation in the compact constructor.

**Do NOT recommend when**: used in 1-2 places with no validation beyond null-checks.

### 1d. CQS Compliance

- **Commands** (change state) should return `void`. Flag methods that return a value AND change state.
- **Queries** (return data) should have no side effects. Logging is acceptable.
- `ItemProcessor.process()` returning `null` to filter items is the Spring Batch contract, not a CQS violation.
- **Do NOT check CQS on**: `@Bean` factory methods, constructors, builder methods, or `@Configuration` classes.

### 1e. Dependency Direction

Domain classes must NOT import: `org.springframework.kafka.*`, `javax.sql.*`, `org.springframework.integration.*`, or similar I/O packages. They must NOT depend on Adapter-zone or Config-zone classes.

Domain classes MAY import: Java standard library, other domain classes, and Spring Batch *interfaces* — these are natural ports:

| Spring Batch Interface | Hex Arch Role | Why |
|----------------------|--------------|-----|
| `ItemProcessor<I, O>` | **Driving Port** | Domain's processing contract. Testable without Spring context. |
| `Partitioner` | **Driving Port** | Work-division strategy. Testable in isolation. |
| `ItemReader<T>` | **Driven Port** | Abstracts data source. Factory pattern is the adapter. |
| `ItemWriter<T>` | **Driven Port** | Abstracts data sink. Factory method is the adapter. |

**Allowed annotations on domain classes**: `@ConfigurationProperties`, `@StepScope`, `@JobScope` (see Guardrail #8). Flag other Spring annotations.

## Step 2: Adapter Zone Review (Light Scrutiny)

### 2a. No Business Logic

Adapters may depend on domain classes and framework libraries. They must NOT contain conditional logic that makes business decisions (beyond error handling or null-checks). If business logic is found, flag it for extraction to the Domain zone.

### 2b. No Infrastructure Leaks

Adapters should not leak infrastructure details into return types that the domain would need to understand.

### 2c. Adapter Port-Name Structure

Apply the Adapter Port-Name Convention (see above). Severity tags:

**[FLAG]** adapter placed directly in a flat `adapters/` package without port-name sub-package.

**[RECOMMEND]** driving adapter in a `controller/` or `controllers/` package — rename to follow the port-name convention.

### 2d. Driven Adapter Port Correspondence

Apply the Driven Adapter Port Rule (see above). `@Configuration` classes that wire adapter beans (e.g., `TransactionKafkaConfig` producing `ConsumerFactory`/`ProducerFactory`) satisfy the rule — they are the adapter's wiring, not misplaced glue. Severity tags:

**[FLAG]** class in a driven adapter package with no port correspondence and no `@Configuration` bean-wiring role.

**[GOOD]** adapter factory or `@Configuration` that produces a natural port implementation.

## Step 3: Config/Application Zone Review (Moderate Scrutiny)

### 3a. Orchestration Only

Config-zone classes should ONLY compose domain and adapter components. If a `@Configuration` class or controller contains conditional logic that makes domain decisions, extract to the Domain zone.

**Exception**: Application-level validation (e.g., `JobController` checking if a job name exists) is routing, not domain logic.

### 3b. CQS on REST APIs

REST endpoints must follow CQS: `GET` = query (no side effects), `POST`/`PUT`/`DELETE` = commands.

## Step 4: Framework Glue Zone Review (Minimal Scrutiny)

Only check: framework glue must not contain business rules. Listeners should only observe, never modify domain behavior based on business rules.

## Step 5: Package Structure Review (Gradual Migration)

**For files IN the current diff** that are not in their target sub-package: recommend moving to the correct `domain/`, `adapters/<portname>/<tech>/`, or `config/` sub-package as a [RECOMMEND] or [CONSIDER].

**For NEW files**: Guide to the correct feature + layer sub-package directly, using the port-name convention for adapters.

**Adapter convention checks**: Apply Steps 2c (port-name structure) and 2d (port correspondence) to files in the current diff that are in adapter packages.

**Do NOT recommend moves for files NOT in the current diff.**

**Shared Kernel rule**: If code in `common/` is only used by one feature, recommend moving it into that feature's package.

## Step 6: Produce the Report

```
## Hex Arch / CQS / DDD Review

### Summary
- Files reviewed: N
- Zone classification: N domain, N adapter, N config, N framework glue
- Findings: N (X flag, Y recommend, Z consider, W good)

### File: path/to/File.java
**Zone**: Domain

#### [GOOD] Natural port usage
`TransactionEnrichmentProcessor` implements `ItemProcessor` — a natural hexagonal
port (a driving port: an interface the domain implements for external callers).
Pure business logic, testable without Spring context.

#### [FLAG] Dependency direction violation
Imports `org.springframework.kafka.SomeClass`, coupling domain to Kafka infrastructure.
**Fix**: Extract Kafka-dependent behavior into an Adapter-zone class.

#### [RECOMMEND] Extract risk scoring (with tradeoff matrix)
| Criteria | Keep in processor | Extract to `RiskScorer` |
|----------|------------------|------------------------|
| Testability | Test via processor | Test directly |
| Complexity | None | New class + import |
| **Recommendation** | **Fine if simple** | **Better if rules grow** |
```

## What NOT to Flag

- Spring Batch listeners implementing framework interfaces for observability
- Constants classes (`BatchStepNames`, `TransactionTopicNames`)
- `@Value` / `@Qualifier` annotations on parameters in Config/Adapter zones
- Test helpers and test data builders
- Framework-required mutable POJOs (when a framework genuinely demands mutability)
- Avro-generated classes

## Concept Glossary

| Term | Explanation |
|------|------------|
| **Hexagonal Architecture** | Business logic has zero dependencies on frameworks or I/O — external access goes through ports implemented by adapters. |
| **Port** | An interface the domain defines or depends on. *Driving ports*: called by driving adapters to enter the domain. *Driven ports*: called by the domain to reach infrastructure, implemented by driven adapters. Spring Batch interfaces serve as natural ports. |
| **Driving Adapter** | Inbound adapter — translates external requests (HTTP, CLI, events) into calls on the domain. Package: `adapters/<portname>/<tech>/` where the verb is an action verb (launching, managing). |
| **Driven Adapter** | Outbound adapter — implements a port to reach infrastructure (database, messaging, filesystem). Package: `adapters/<portname>/<tech>/` where the verb is an infrastructure verb (persisting, reading, streaming). Must correspond to a port. |
| **Value Object** | Immutable, identity-free, equality by attributes. Best as Java `record` classes. |
| **Entity** | Object with unique identity that persists over time. Two entities with same attributes but different IDs are different. |
| **CQS** | Every method either changes state (command, returns void) or returns data (query, no side effects) — never both. |
| **Dependency Direction** | Dependencies point inward: adapters depend on domain, never the reverse. |
| **Natural Port** | Framework interface (like `ItemProcessor`) that serves as a hexagonal port. No custom interface needed. |
| **Bounded Context** | A boundary within which a domain model applies. Maps to a feature package. |
| **Shared Kernel** | Code shared across bounded contexts. Should be minimal. |

## Tone

Present findings as a thinking partner, not a linter. Explain the "why" behind each finding. Acknowledge what works before suggesting improvements. Draft concrete code when recommending changes.
