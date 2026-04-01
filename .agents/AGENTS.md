# CLAUDE.md — k8s-batch Project Guide

## Project Overview

Spring Boot 4.0.3 + Spring Batch 6.x reference project for horizontally-scalable batch processing on Kubernetes. Four batch jobs demonstrate different patterns: two CSV-to-DB ETL jobs use remote partitioning via Kafka (with standalone fallback), a transaction enrichment job reads Avro events from Kafka, enriches them, and writes to both MySQL and a Kafka output topic, and a rules engine PoC job applies financial business rules using Drools, EVRete, KIE DMN decision tables, or Drools rule units, toggled via `batch.rules.engine` property.

## Tech Stack

- **Java 21**, **Spring Boot 4.0.3**, **Spring Batch 6.0.2**, **Spring Data JPA 4.x / Hibernate 7.x** (CRUD service)
- **MySQL 8.4 / Oracle DB** — JobRepository + application data
- **Kafka (Confluent Platform 7.9.0, KRaft mode)** — remote partitioning messaging + event streaming
- **Avro 1.12 + Confluent Schema Registry** — event serialization for transaction enrichment job
- **Helm 3** — Kubernetes deployment
- **Testcontainers 2.0.3** — integration tests
- **Liquibase** — database migrations (MySQL + Oracle compatible XML changelogs)
- **Drools 10.x (classic + rule units) + EVRete + KIE DMN** — rules engine PoC (toggled via `batch.rules.engine` property)

## Prerequisites

- **JDK 21** (e.g., Temurin)
- **Docker** (not Podman — K3s E2E tests need privileged containers)
- **Maven 3.9+**
- **helm** CLI + `helm-unittest` plugin (`helm plugin install https://github.com/helm-unittest/helm-unittest`)

## Build & Test

```bash
mvn clean compile                              # compile all modules
mvn test-compile                               # compile including test sources
mvn -pl k8s-batch-integration-tests -am verify  # run integration tests (quiet: output in target/failsafe-reports/)
mvn -pl k8s-batch-e2e-tests -am verify          # run E2E tests (quiet: output in target/failsafe-reports/)
mvn verify -DskipE2E=true                      # run integration tests, skip E2E
mvn -pl k8s-batch-integration-tests -am verify -Dtest.log.level=DEBUG -DredirectTestOutputToFile=false  # verbose: full output on console
mvn package -DskipTests                        # build JAR without tests
docker build -t k8s-batch:e2e .                # build batch app Docker image for E2E tests
docker build -f Dockerfile.gateway -t k8s-batch-api-gateway:e2e .  # build gateway image
docker build -f Dockerfile.crud -t k8s-batch-crud:e2e .            # build CRUD service image
mvn -pl k8s-batch-crud-tests -am verify         # run CRUD integration tests
docker-compose up -d                           # local stack (app + MySQL + Kafka)
helm lint helm/k8s-batch                       # validate Helm chart
helm unittest helm/k8s-batch                   # run Helm unit tests (85 tests, ~140ms)
mvn validate                                   # verify Apache-2.0 SPDX headers + JavaDoc checks
mvn -Plicense-fix validate                     # auto-apply missing SPDX headers
mvn checkstyle:check                           # run JavaDoc checks only (standalone)
mvn checkstyle:check -Dcheckstyle.failOnViolation=false  # JavaDoc checks, report-only
mvn validate -Dskip.checkstyle=true            # skip JavaDoc checks entirely
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `k8s-batch-rules-kie` | KIE-based rules engine adapters (DMN + Drools Rule Units) and rules PoC domain types |
| `k8s-batch-jobs` | Main Spring Boot application (REST, batch jobs, config). Depends on `k8s-batch-rules-kie`. Owns batch Liquibase changelogs (001-003) |
| `k8s-batch-integration-tests` | Testcontainers integration tests for batch (separate to keep test infra out of production artifact) |
| `k8s-batch-e2e-tests` | E2E tests using Testcontainers K3s — deploys Helm chart into real K8s cluster |
| `k8s-batch-api-gateway` | Spring Cloud Gateway Server MVC (Servlet-based, not reactive). Routes to batch and CRUD backends |
| `k8s-batch-api-gateway-tests` | Integration tests for the API gateway (WireMock backend, circuit breaker) |
| `k8s-batch-crud` | JPA/Hibernate CRUD microservice for Customer/Account management. Owns its own `k8scrud` database and Liquibase changelogs (004-006) |
| `k8s-batch-crud-tests` | Integration tests for the CRUD service (@DataJpaTest slices + @SpringBootTest REST round-trips) |

## Rules Files Index

**IMPORTANT**: When creating, renaming, or deleting a rules file in `.agents/rules/`, update this table in the same commit. This index is the sync map between CLAUDE.md and the glob-matched rules files — it must always reflect the current set of rules.

| Rule File | Globs | Content |
|-----------|-------|---------|
| `rules/git.md` | *(always)* | Commit signing, squash merge strategy, commit message format |
| `rules/spring-batch-6.md` | `**/*.java` | SB6 package imports, chunk builder API, Kafka serializers, test utilities |
| `rules/spring-boot-4.md` | `**/*.java` | SB4 migration rules, Testcontainers 2.x conventions |
| `rules/coding-standards.md` | `**/*.java`, `**/*.sh` | Image/version constants, logging, AutoCloseable, license/SPDX, checkstyle |
| `rules/helm-conventions.md` | `helm/**`, `**/e2e/**/*.java`, `.github/workflows/**` | Helm chart conventions, MySQL probes, CI rules, E2E image sync |
| `rules/database-per-service.md` | `helm/**`, `**/application.yml`, `**/changelog/**`, `docker-compose.yml`, `**/ContainerHolder.java` | Database-per-service topology, Liquibase ownership, MySQL multi-DB conventions |

## CRUD Service (JPA/Hibernate 7.x)

The CRUD service (`k8s-batch-crud`) is a separate Spring Boot application using JPA/Hibernate 7.x for Customer/Account management.

- **Hexagonal architecture**: same `adapters/<portname>/<technology>/` convention as the batch module. Spring Data JPA repositories ARE the natural persistence port (no wrapper interfaces needed)
- **Services in `domain/`**: application services use `@Service @Transactional(readOnly=true)` at class level, write methods override with `@Transactional`. No port interfaces (one implementation = noise)
- **Entity design**: `protected` no-arg constructor, business constructor enforcing invariants, `@NaturalId` for business keys, `@Version` on all mutable entities, `equals`/`hashCode` on natural ID
- **`@ManyToOne(fetch = FetchType.LAZY)`** always — Hibernate defaults to EAGER which causes N+1
- **`Set` for `@OneToMany`** — `List` without `@OrderColumn` uses bag semantics (full join table rebuild)
- **`@EntityGraph`** for eager-fetch queries — avoids N+1 when loading associations
- **DTO records** for REST responses — entities stay inside the transaction boundary

### Hibernate 7.x SEQUENCE Emulation on MySQL

MySQL 8.4 has no native `CREATE SEQUENCE`. Hibernate emulates via **individual tables per `@SequenceGenerator`** (NOT a shared `hibernate_sequences` table). Each table has a single `next_val` column.

```java
@Id
@GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "customer_seq")
@SequenceGenerator(name = "customer_seq", sequenceName = "customer_sequence", allocationSize = 50)
private Long id;
```

This creates a `customer_sequence` table with one row. With `ddl-auto: validate`, Hibernate will NOT create these tables — they must be created by Liquibase changelogs (see `006-create-hibernate-sequences.xml`).

### JPA Production Configuration

```yaml
spring.jpa:
  open-in-view: false          # CRITICAL — prevents lazy loading outside @Transactional
  hibernate.ddl-auto: validate # Schema managed by Liquibase, Hibernate only validates
spring.datasource.hikari:
  auto-commit: false           # Paired with provider_disables_autocommit
  leak-detection-threshold: 60000
hibernate.connection.provider_disables_autocommit: true  # Skips setAutoCommit(false) per TX
```

### JdbcTemplate with auto-commit=false (Test Cleanup Gotcha)

When HikariCP is configured with `auto-commit: false` (the JPA production best practice), `JdbcTemplate.execute()` calls outside a Spring-managed `@Transactional` each get their own connection with an **uncommitted** transaction. Multiple DELETEs appear to succeed but are invisible to each other across connections — causing FK constraint failures even in correct FK-safe order.

**Fix**: Wrap multi-statement test cleanup in `TransactionTemplate.executeWithoutResult()`:

```java
@Autowired private TransactionTemplate transactionTemplate;

private void doCleanup() {
    transactionTemplate.executeWithoutResult(status -> {
        jdbcTemplate.execute("DELETE FROM accounts");
        jdbcTemplate.execute("DELETE FROM customers");
    });
}
```

This ensures both DELETEs run in a single committed transaction on the same connection.

## Database Topology

The project uses a **database-per-service** pattern: one shared MySQL StatefulSet, but each microservice owns its own logical database. This gives strong schema isolation (no cross-domain FKs, independent Liquibase migration timelines) without multiplying infrastructure.

| Service | Database | Tables |
|---------|----------|--------|
| `k8s-batch-jobs` | `k8sbatch` | Spring Batch meta-tables (`BATCH_*`) + batch app tables (`target_records`, `enriched_transactions`, `rules_poc_enriched_transactions`) |
| `k8s-batch-crud` | `k8scrud` | CRUD domain tables (`customers`, `accounts`) + Hibernate sequence tables (`customer_sequence`, `account_sequence`) |

In Kubernetes, `mysql.auth.database` creates the primary `k8sbatch` database via `MYSQL_DATABASE` env var. Additional databases are created by the init ConfigMap `00-create-databases.sql` (templated from `mysql.auth.additionalDatabases` in `values.yaml`). In Docker Compose, `config/mysql-init/00-create-databases.sql` serves the same role.

## Per-Service Liquibase Changelogs

Each service owns its own Liquibase changelogs under `src/main/resources/db/changelog/`. When adding a new CRUD service, create its changelogs in its own module — do NOT modify other services' changelogs.

- **Batch service**: `k8s-batch-jobs/src/main/resources/db/changelog/` — changelogs 001-003
- **CRUD service**: `k8s-batch-crud/src/main/resources/db/changelog/` — changelogs 004-006

**CRITICAL**: After moving changelogs between modules, always run `mvn clean` before compiling. Incremental builds do NOT remove files deleted from `src/` — stale changelogs in `target/classes/` cause `Found 2 files with the path` Liquibase errors.

**Dockerfile COPY rules**: When adding a new module to the parent POM `<modules>`, ALL Dockerfiles must add `COPY <new-module>/pom.xml <new-module>/` for the module's POM. Maven resolves the full reactor from the parent POM and fails if any `<module>` directory is missing. Source directories (`src/`) only need to be COPYed for modules in the build's `-pl ... -am` dependency chain.

## Profile Strategy

Remote partitioning uses composable profiles: a shared `remote-partitioning` base profile paired with a transport-specific sub-profile. **BOTH profiles must be activated together** — `remote-partitioning` alone is NOT sufficient (the transport-specific `IntegrationFlow` beans will not be created, causing `Dispatcher has no subscribers` errors).

| Profile | Activation | Broker | Description |
|---------|-----------|--------|-------------|
| `remote-partitioning,remote-kafka` | Default (Helm `values.yaml`) | Kafka | Kafka-based remote partitioning + transaction enrichment job. **Both profiles required.** |
| `remote-partitioning,remote-jms` | `--spring.profiles.active=remote-partitioning,remote-jms` | RabbitMQ or SQS | JMS-based remote partitioning via `spring-integration-jms`. Broker selected by `ConnectionFactory` bean (`RMQConnectionFactory` for RabbitMQ, `SQSConnectionFactory` for AWS SQS). |
| `standalone` | `--spring.profiles.active=standalone` | None | In-process `TaskExecutorPartitionHandler` |
| `integration-test` | Test classes only | Depends on test | Test-specific config (schema auto-create, WARN logging by default — override with `-Dtest.log.level=DEBUG`) |

**Architecture**: `RemotePartitioningBaseConfig` (`@Profile("remote-partitioning")`) provides shared beans (manager step factory, `DirectChannel`, `StepExecutionRequestHandler`, manager steps). Transport configs provide outbound/inbound `IntegrationFlow` beans: `RemotePartitioningKafkaConfig` (`@Profile("remote-kafka")`) for native Kafka, `RemotePartitioningJmsConfig` (`@Profile("remote-jms")`) for JMS-compatible brokers (RabbitMQ, SQS).

**Transaction enrichment job** is gated on `@Profile("remote-kafka")` — it requires Kafka (Avro + Schema Registry) regardless of which transport is used for partitioning.

## Batch Job Design

### CSV ETL Jobs (fileRangeEtlJob, multiFileEtlJob)

Both CSV jobs follow the same pattern: **Partitioner → Manager Step → Worker Steps**

- `FileRangePartitioner` — splits a CSV by line ranges
- `MultiFilePartitioner` — assigns one CSV file per partition
- Manager step: `RemotePartitioningBaseConfig` (shared) + transport config (Kafka/AMQP/SQS) or `StandaloneJobConfig` (local threads)
- Worker step: `FlatFileItemReader` → `CsvRecordProcessor` → `JdbcBatchItemWriter`

### Transaction Enrichment Job (transactionEnrichmentJob)

Single chunk step (not partitioned) — reads Avro `TransactionEvent` from Kafka, enriches with exchange rate + risk score, writes to both MySQL and Kafka output topic via `CompositeItemWriter`.

- **Config**: `TransactionEnrichmentJobConfig` + `TransactionKafkaConfig` (both `@Profile("remote-kafka")`)
- **Properties**: `TransactionJobProperties` bound to `batch.transaction.*`
- **Avro schemas**: `k8s-batch-jobs/src/main/avro/` (generates Java classes via `avro-maven-plugin`)
- **DB writer**: `EnrichedTransactionWriter` — upsert via `ON DUPLICATE KEY UPDATE` for idempotency
- **Kafka transactions**: `batch.transaction.kafka-transactions-enabled` (default: `false`). When `true`, `ProducerFactory` gets a `transactionIdPrefix`, enabling best-effort 1PC coordination with `DataSourceTransactionManager` via Spring's `TransactionSynchronizationManager`. Consumer uses `read_committed` isolation.
- **Parallelism**: comes from Kafka partition assignment across pod replicas, not Spring Batch partitioning
- `@StepScope` is mandatory on reader/processor/writer/partitioner beans to enable partition-specific `ExecutionContext` and job parameter injection
- **Remote partitioning manager** uses `JobRepository` polling (not reply channels) to detect worker completion — avoids `StepExecution` serialization issues through Kafka
- **Worker-side processing**: `StepExecutionRequestHandler` + `BeanFactoryStepLocator` in `RemotePartitioningJobConfig` handles incoming partition requests
- **`@Qualifier`** is required on `Step` bean parameters in job/manager configs to resolve ambiguity when multiple worker steps exist
- **`BatchStepNames`** constants class — always use these constants for job/step names in builders and `@Qualifier` annotations (never raw strings)
- **`BatchPartitionProperties`** includes `timeoutMs` — configurable via `batch.partition.timeout-ms` (default 60000, overridden to 15000 in integration-test profile)
- **`TaskExecutorPartitionHandler`** (standalone mode) has no timeout API — JUnit `@Timeout` is the only backstop

### Rules Engine PoC Job (rulesEnginePocJob)

Single non-partitioned chunk step — reads financial transactions from CSV, applies business rules via Drools DRL, EVRete Java API, KIE DMN decision tables, or Drools rule units, writes enriched results to MySQL. PoC for evaluating rules engine alternatives.

- **Module split**: Domain types (port, value objects, shared fact) and KIE-based adapters (DMN, Drools Rule Units) live in `k8s-batch-rules-kie`. Classic Drools DRL and EVRete adapters stay in `k8s-batch-jobs`. Dependency direction: `k8s-batch-jobs` → `k8s-batch-rules-kie` (one-directional). Same Java packages are preserved — Spring Boot auto-scans `@Component` beans across both modules.
- **Toggle**: `batch.rules.engine=drools` (default), `evrete`, `dmn`, or `drools-ruleunit` — selects the active `TransactionRulesEvaluator` implementation via `@ConditionalOnProperty`
- **Config**: `RulesEnginePocJobConfig` + `RulesEngineProperties` (bound to `batch.rules.*`) — stay in `k8s-batch-jobs`
- **Domain port**: `TransactionRulesEvaluator` interface — custom driven port with four adapter implementations (in `k8s-batch-rules-kie`)
- **Domain constants**: `EnrichmentRuleConstants` record — exchange rates, risk thresholds, compliance rules (in `k8s-batch-rules-kie`). Shared by Drools (DRL `global`), EVRete (constructor injection), and Drools rule units (plain field on `TransactionEnrichmentUnit`). DMN adapter uses it for exchange rates; risk/compliance thresholds are in the DMN model.
- **DRL**: `k8s-batch-jobs/src/main/resources/rules/transaction-enrichment.drl` — 4 rules using `EnrichmentRuleConstants` global (not hardcoded constants). Imports resolve from `k8s-batch-rules-kie` at DRL compile time.
- **DMN**: `k8s-batch-rules-kie/src/main/resources/dmn/risk-assessment.dmn` — two decision tables (Risk Score, Compliance Review) with dependency graph. Used by DMN adapter via `DMNRuntime` API loaded through `KieFileSystem`.
- **Adapter fact**: `TransactionFact` — mutable JavaBean in `k8s-batch-rules-kie` for rules engine sessions (required by Drools DRL `then` blocks). Uses `RiskScore` enum (not String). Factory methods: `from(FinancialTransaction)` and `toEnrichedTransaction(engineName, processedAt)`
- **Rule-unit adapter**: `DroolsRuleUnitTransactionRulesEvaluator` in `k8s-batch-rules-kie` uses `RuleUnitProvider` API with `TransactionEnrichmentUnit` (`RuleUnitData` + `DataStore`). FX/USD conversion in Java, risk scoring + compliance in DRL with OOPath syntax. No `kie-maven-plugin` needed — DRL is discovered at runtime. Designed for future decomposition into multiple units as the rule set grows.
- **Rule-unit DRL**: co-located with the unit class package at `k8s-batch-rules-kie/src/main/resources/com/cominotti/.../droolsruleunit/transaction-enrichment-unit.drl` — 2 rules (risk scoring, compliance) using OOPath and unit-scoped field access instead of globals. Must be on the classpath matching the unit's Java package for `RuleUnitProvider` auto-discovery.
- **Path validation**: `BatchFileProperties.requireWithinAllowedBase()` — shared CWE-22 path traversal prevention (used by all job configs)

## Job REST API

- **`JobController`** at `/api/jobs` — async job launch via `JobOperator` (backed by `JobOperatorFactoryBean`)
- `POST /api/jobs/{jobName}` — accepts `Map<String, String>` parameters, returns `JobExecutionResponse` with HTTP 202
- `GET /api/jobs/{jobName}/executions/{executionId}` — polls execution status
- Unknown job names return HTTP 400 (not 500)
- `Map<String, Job> jobRegistry` auto-wires all `Job` beans by Spring bean name
- **`AsyncJobOperatorConfig`** defines a `@Bean("asyncJobOperator")` backed by `JobOperatorFactoryBean` with `SimpleAsyncTaskExecutor` — POST returns immediately while the job runs in a background thread. The auto-configured synchronous `JobOperator` coexists (used by tests via `JobOperatorTestUtils`)

## E2E Test Rules

- **Test class pattern**: `**/*E2E.java` (distinct from `*IT.java` integration tests)
- **Skip flag**: `-DskipE2E=true` (default: `false`)
- **Prerequisites**: Docker daemon (K3s needs privileged containers — Podman rootless won't work), `helm` CLI on PATH
- **`K3sClusterManager`** singleton manages K3s lifecycle, follows `ContainerHolder` pattern
- **Image loading**: `docker save` → `copyFileToContainer` → `ctr images import`; guarded by `loadedImages` set to avoid redundant loads
- **`PodUtils`** — stateless pod inspection utilities: `isReady()`, `findTerminalError()`, `isUnschedulable()`, `describeInitProgress()`, `hasRunningMainContainer()`
- **`DeploymentWaiter`** — fast-failure polling loop (package-private, used by `K3sClusterManager`). Detects terminal errors (ImagePullBackOff, CrashLoopBackOff, OOMKilled) immediately, tracks progress stall (120s), streams container logs for stuck pods (after 60s). Delegates to `PodDiagnostics` on failure.
- **`AbstractE2ETest`** base class provides `@BeforeEach cleanTestData()`, `requiresKafka()`/`requiresGateway()`/`requiresCrud()` override hooks, port-forward setup. The CRUD image is always loaded (deployed in all profiles) but `crudClient` is only created when `requiresCrud()=true`
- **Helm rendering**: `HelmRenderer` shells out to `helm template`; uses `redirectErrorStream(true)` to avoid deadlock
- **Rendered manifests are cached** in `K3sClusterManager` for reuse during teardown
- **E2E test data isolation**: When querying `BATCH_STEP_EXECUTION`, always scope by `JOB_EXECUTION_ID` — use `MysqlVerifier.countStepExecutionsForJob()`, not the unscoped query. Multiple test methods run the same job, and step executions accumulate across runs.

## Claude Code Automations

- **`/helm-validate`** — runs helm lint, unittest, and template render
- **`/run-integration-tests`** — Docker + Helm prerequisite checks, Docker image build, then runs both integration tests and E2E tests
- **`/doc-review`** — reviews documentation quality (JavaDoc, comments, README) for changed files after code changes. Uses Checkstyle XML report as deterministic baseline, then applies AI-driven semantic review
- **`helm-reviewer`** subagent — reviews Helm changes against project conventions (invoked automatically during PR reviews)
- **`/spring-migration`** — reviews Java code for deprecated Spring, Spring Batch, Spring Boot, and Fabric8 APIs; checks imports, method calls, and configuration against current non-deprecated alternatives
- **`.mcp.json`** — shares context7 MCP server config with collaborators
