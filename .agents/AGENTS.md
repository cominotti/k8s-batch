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
| `k8s-batch-common` | Shared Liquibase changelogs for database schema management across services (zero Java code, zero dependencies) |
| `k8s-batch-rules-kie` | KIE-based rules engine adapters (DMN + Drools Rule Units) and rules PoC domain types |
| `k8s-batch-jobs` | Main Spring Boot application (REST, batch jobs, config). Depends on `k8s-batch-rules-kie` and `k8s-batch-common` |
| `k8s-batch-integration-tests` | Testcontainers integration tests for batch (separate to keep test infra out of production artifact) |
| `k8s-batch-e2e-tests` | E2E tests using Testcontainers K3s — deploys Helm chart into real K8s cluster |
| `k8s-batch-api-gateway` | Spring Cloud Gateway Server MVC (Servlet-based, not reactive). Routes to batch and CRUD backends |
| `k8s-batch-api-gateway-tests` | Integration tests for the API gateway (WireMock backend, circuit breaker) |
| `k8s-batch-crud` | JPA/Hibernate CRUD microservice for Customer/Account management. Shares same MySQL database via `k8s-batch-common` changelogs |
| `k8s-batch-crud-tests` | Integration tests for the CRUD service (@DataJpaTest slices + @SpringBootTest REST round-trips) |

## Spring Batch 6.x Package Rules

Spring Batch 6 restructured packages. **Always use these imports**:

| Class | Package |
|-------|---------|
| `Job` | `org.springframework.batch.core.job.Job` |
| `JobExecution` | `org.springframework.batch.core.job.JobExecution` |
| `JobParameters` / `JobParametersBuilder` | `org.springframework.batch.core.job.parameters.*` |
| `Step` | `org.springframework.batch.core.step.Step` |
| `StepExecution` | `org.springframework.batch.core.step.StepExecution` |
| `StepBuilder` | `org.springframework.batch.core.step.builder.StepBuilder` |
| `JobBuilder` | `org.springframework.batch.core.job.builder.JobBuilder` |
| `Partitioner` | `org.springframework.batch.core.partition.Partitioner` |
| `BatchStatus` | `org.springframework.batch.core.BatchStatus` |
| `ExecutionContext` | `org.springframework.batch.infrastructure.item.ExecutionContext` |
| `ItemProcessor` | `org.springframework.batch.infrastructure.item.ItemProcessor` |
| `FlatFileItemReader` | `org.springframework.batch.infrastructure.item.file.FlatFileItemReader` |
| `JdbcBatchItemWriter` | `org.springframework.batch.infrastructure.item.database.JdbcBatchItemWriter` |
| `JobExecutionListener` | `org.springframework.batch.core.listener.JobExecutionListener` |
| `StepExecutionListener` | `org.springframework.batch.core.listener.StepExecutionListener` |
| `ExitStatus` | `org.springframework.batch.core.ExitStatus` |

**Never use** the old `org.springframework.batch.item.*` or `org.springframework.batch.core.Job` (top-level) packages.

## Chunk Step Builder Pattern

Use the new builder API (the old `chunk(int, TransactionManager)` is deprecated):

```java
new StepBuilder("stepName", jobRepository)
    .<Input, Output>chunk(chunkSize)
    .transactionManager(transactionManager)
    .reader(reader)
    .processor(processor)
    .writer(writer)
    .build();
```

## Spring Kafka Serializers

Use `JacksonJsonSerializer` / `JacksonJsonDeserializer` for Kafka partition request messages (the old `JsonSerializer` / `JsonDeserializer` are deprecated). Set `JacksonJsonDeserializer.TRUSTED_PACKAGES` to `"*"` for remote partitioning messages.

## Testcontainers 2.x Rules

- **Artifact names** use `testcontainers-` prefix: `testcontainers-mysql`, `testcontainers-redpanda`, `testcontainers-junit-jupiter`
- **MySQL**: `org.testcontainers.mysql.MySQLContainer` (not `org.testcontainers.containers.MySQLContainer`). **Non-generic** — no `<?>` wildcard.
- **Redpanda** (replaces Confluent Kafka for integration tests): `org.testcontainers.redpanda.RedpandaContainer` — Kafka-compatible broker with built-in Schema Registry. Use `getBootstrapServers()` for Kafka API, `getSchemaRegistryAddress()` for Schema Registry URL. Much faster startup (~5-10s vs 30-60s for Confluent Kafka). E2E tests still use real Confluent Kafka via the Helm chart.
- **`@ServiceConnection`** handles JDBC wiring automatically — never use `@DynamicPropertySource` for MySQL
- **Kafka bootstrap servers** are set via `System.setProperty` in `ContainerHolder` (Kafka `@ServiceConnection` requires `spring-boot-kafka` which conflicts with manual `RemotePartitioningKafkaConfig`)
- **Schema Registry URL** is set via `System.setProperty("spring.kafka.properties.schema.registry.url", REDPANDA.getSchemaRegistryAddress())` in `ContainerHolder.startAll()`
- **RabbitMQ (JMS tests)**: `JmsContainersConfig` creates a JMS `ConnectionFactory` bean (`RMQConnectionFactory`) wired to the Testcontainer RabbitMQ instance
- **Container lifecycle** is managed by `ContainerHolder` (not directly in config classes):
  - `ContainerHolder.startMysqlOnly()` — standalone tests, skips all brokers
  - `ContainerHolder.startAll()` — parallel startup of MySQL + Redpanda for Kafka remote tests
  - `ContainerHolder.startMysqlAndRabbitMq()` — parallel startup for JMS remote tests
  - `SharedContainersConfig` delegates to `ContainerHolder.startAll()` + creates Kafka topics
  - `JmsContainersConfig` delegates to `ContainerHolder.startMysqlAndRabbitMq()` + creates JMS `ConnectionFactory`
  - `MysqlOnlyContainersConfig` delegates to `ContainerHolder.startMysqlOnly()`
- **Parallel startup**: use `Startables.deepStart(Stream.of(MYSQL, REDPANDA)).join()` — not sequential `.start()` calls

## Spring Boot 4.x Rules

- `TestRestTemplate` is removed. Use `RestClient` with `@LocalServerPort`.
- `spring-boot-starter-batch-jdbc` is required explicitly for database-backed `JobRepository`.
- `spring.batch.job.enabled: false` prevents auto-launching jobs at startup.
- **Auto-configuration modules extracted**: `spring-boot-liquibase`, `spring-boot-integration`, `spring-boot-kafka` are separate dependencies in SB4 (not in `spring-boot-autoconfigure`).
- **JPA test modules restructured in SB4**: `@DataJpaTest`, `TestEntityManager`, and `@AutoConfigureTestDatabase` moved to NEW packages and separate JARs:
  - `@DataJpaTest` → `spring-boot-data-jpa-test` at `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`
  - `TestEntityManager` → `spring-boot-jpa-test` at `org.springframework.boot.jpa.test.autoconfigure.TestEntityManager`
  - `@AutoConfigureTestDatabase` → `spring-boot-jdbc-test` at `org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase`
  - **All three JARs must be declared explicitly** in test module POMs — they are NOT transitive from `spring-boot-starter-test`
- **Multi-module `@SpringBootTest`**: always use `classes = K8sBatchApplication.class` — Spring can't find it by package scanning across modules.
- **`spring-boot-maven-plugin` classifier**: use `<classifier>exec</classifier>` so dependent modules see the original JAR, not the fat JAR.

## Test Utilities

- **Use `JobOperatorTestUtils`** (not the deprecated `JobLauncherTestUtils`)
- Constructor: `new JobOperatorTestUtils(jobOperator, jobRepository)`
- Launch jobs with `startJob(params)` (not `launchJob`)
- **`JobRepositoryTestUtils`** is not deprecated — use for cleanup: `removeJobExecutions()`

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

## Shared Liquibase Changelogs (`k8s-batch-common`)

All Liquibase changelogs live in `k8s-batch-common/src/main/resources/db/changelog/`. Both the batch service and CRUD service depend on `k8s-batch-common`, so changelogs are on both classpaths. Liquibase's `DATABASECHANGELOGLOCK` prevents concurrent migration races.

**CRITICAL**: When moving changelogs between modules, always run `mvn clean` before compiling. Incremental builds do NOT remove files deleted from `src/` — stale changelogs in `target/classes/` cause `Found 2 files with the path` Liquibase errors.

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

## Image and Version Constants Rule

**Never hardcode Docker image names, tags, or version numbers as string literals.** They must be defined in constants classes or values files and referenced by name.

| Scope | Where to define | Example constants |
|-------|----------------|-------------------|
| Integration tests | `TestContainerImages` (in `it/config/`) | `MYSQL_IMAGE`, `REDPANDA_IMAGE` |
| E2E tests | `E2EContainerImages` (in `e2e/`) | `APP_IMAGE`, `CRUD_IMAGE`, `GATEWAY_IMAGE`, `MYSQL_IMAGE`, `KAFKA_IMAGE`, `K3S_IMAGE`, `SCHEMA_REGISTRY_IMAGE` |
| CRUD integration tests | `CrudTestContainerImages` (in `crud/it/config/`) | `MYSQL_IMAGE` |
| Helm chart | `values.yaml` (`global.initImage`, `*.image.repository/tag`) | busybox, kafka, mysql images |

**Pattern**: `public final class` with `private` constructor and `public static final String` fields (same as `BatchStepNames`).

**Log messages** must reference the constant or use a container's `getDockerImageName()` — never duplicate the version string:

```java
// CORRECT
log.info("Starting container | image={}", TestContainerImages.REDPANDA_IMAGE);
log.info("Container started | image={}", REDPANDA.getDockerImageName());

// WRONG — duplicates the version string
log.info("Starting Redpanda container (redpanda:v25.1.9)...");
```

## Logging Conventions

- **Logger declaration**: plain SLF4J `private static final Logger log = LoggerFactory.getLogger(ClassName.class)` — no Lombok
- **Log format**: `key=value | key=value` pipe-separated structured fields for machine parseability
- **Configuration**: `logback-spring.xml` with `<springProfile>` blocks — do NOT use `logging.level` in application YAML files (avoids precedence confusion)
- **Profile levels**: production=INFO, standalone=INFO (`StandaloneJobConfig` at DEBUG), integration-test=WARN (override with `-Dtest.log.level=DEBUG`)
- **Batch listeners**: `LoggingJobExecutionListener` and `LoggingStepExecutionListener` are `@Component` beans — register via `.listener()` on `JobBuilder` and `StepBuilder`/`RemotePartitioningManagerStepBuilder` respectively
- **Duration utility**: `BatchDurationUtils.between(start, end)` for null-safe `Duration.between()` — shared by both listeners
- **Log levels**: INFO for business events (job/step lifecycle, partition creation, config init), DEBUG for per-item details (filtered records, reader/writer creation), ERROR for failures, WARN for unexpected-but-non-fatal statuses

## AutoCloseable / Resource Management

**Always use try-with-resources** for objects that implement `AutoCloseable` or `Closeable`. Never use manual `try-finally` with `.close()` or `.dispose()`.

```java
// CORRECT
try (KieSession session = kieContainer.newKieSession()) {
    session.insert(fact);
    session.fireAllRules();
}

// WRONG — manual try-finally
KieSession session = kieContainer.newKieSession();
try {
    session.insert(fact);
    session.fireAllRules();
} finally {
    session.dispose();
}
```

**Scope**: sessions (KieSession, StatefulSession, RuleUnitInstance), streams, connections, prepared statements, readers/writers — any short-lived resource acquired and released within a method.

**Exception**: long-lived resources managed by Spring's bean lifecycle (e.g., `KnowledgeService` cleaned up via `@PreDestroy`) do not use try-with-resources.

## Helm Chart Conventions

- Schema initialization: MySQL `docker-entrypoint-initdb.d` ConfigMap, not `initialize-schema: always`
- App Deployment omits `replicas` when HPA is enabled (prevents Helm/HPA conflicts)
- `checksum/config` annotation on Deployment triggers rolling restart on ConfigMap changes
- Init containers gate app startup until MySQL, Kafka, and Schema Registry (when enabled) are reachable
- Kafka runs in **KRaft mode** (no Zookeeper). Internal topic replication factors (`KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR`, `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR`) are derived from `min(kafka.replicaCount, 3)` in the StatefulSet template — required for single-broker deployments (E2E uses `replicaCount: 1`)
- **Schema Registry** uses a `Deployment` (stateless — stores schemas in `_schemas` Kafka topic). Gated on `schemaRegistry.enabled AND features.schemaRegistry`. Schema Registry URL wired to app via `SPRING_KAFKA_PROPERTIES_SCHEMA_REGISTRY_URL` env var.
- **Topic creation**: `job-create-topics.yaml` uses `range $key, $val` over `kafka.topics` map (skipping `extra` list). Adding a new topic is a values-only change — no template editing needed
- **Init container image** (`busybox`) is parameterized via `global.initImage` in `values.yaml` — never hardcode it in templates
- **Helm unit tests**: `helm/k8s-batch/tests/*_test.yaml` using `helm-unittest` plugin. Run with `helm unittest helm/k8s-batch`
- **Template dependencies**: when a template references another (e.g., deployment includes configmap for checksum), both must be listed in `templates:` in the test file, and use `documentSelector` with `skipEmptyTemplates: true` to target the correct document
- **CI validation**: `.github/workflows/helm-validate.yml` runs helm lint, unittest, kubeconform, and K3s smoke test. The K3s smoke test pre-pulls infrastructure images (`docker pull`) and imports them into K3s (`k3d image import`) before `helm install`. **Image versions in the CI workflow must always match their source of truth**: `values.yaml` for Helm-managed images (`mysql`, `kafka`, `schemaRegistry`, `global.initImage`) and `E2EContainerImages` / `TestContainerImages` for test-managed images. When bumping an image version, update both the source of truth and the CI workflow's pre-pull step in the same commit
- **`values.yaml` documentation**: every non-trivial parameter must have a YAML comment explaining what it does, why the value was chosen, and how it relates to other parameters. Use `# --` prefix for block-level descriptions above a parameter group, and inline `#` comments on individual fields when the reasoning is not obvious from the name alone. This is especially important for probes, resource limits, timeouts, and any value derived from a formula (e.g., `periodSeconds * failureThreshold = max init window`)
- **MySQL probes must use `tcpSocket`, not `mysqladmin`**: `mysqladmin ping -h localhost` connects via Unix socket, but the MySQL Docker image creates the socket at `/var/lib/mysql/mysql.sock` while `mysqladmin` looks for it at the compiled-in default path — causing probe failures even when MySQL is listening on port 3306. `tcpSocket` on port 3306 avoids this entirely. This applies to all three probes (startup, liveness, readiness).
- **MySQL `startupProbe` is required**: MySQL first-start initialization (data dir creation, InnoDB init, `docker-entrypoint-initdb.d` DDL scripts) can take 5-10 minutes on CI runners. Without a startup probe, the liveness probe kills the container before init completes, causing crash-loops. The startup probe gates liveness/readiness during this phase. The Docker entrypoint runs the temporary init mysqld with `--skip-networking`, so port 3306 only opens after init finishes — making `tcpSocket` safe for startup detection.
- **App Deployment `progressDeadlineSeconds`**: defaults to 900s (configurable via `app.progressDeadlineSeconds`). Kubernetes's default of 600s is too short when init containers wait for slow MySQL first-start — the Deployment declares itself `Failed` before the Helm timeout fires.
- **E2E values image tags must match `E2EContainerImages` constants**: The E2E Helm values files (`e2e-remote.yaml`, `e2e-standalone.yaml`) specify `image.tag` for each component. These MUST match the constants in `E2EContainerImages.java` (e.g., `MYSQL_IMAGE = "mysql:8.4"` → `mysql.image.tag: "8.4"`). Mismatches cause `ErrImageNeverPull` because K3s loads one version but the Helm chart deploys a different one with `pullPolicy: Never`.
- **E2E version assertions must derive from constants, not hardcode**: Test assertions like `assertThat(version).startsWith("8.4")` should derive the expected value from `TestContainerImages.MYSQL_IMAGE`, not hardcode it. Otherwise bumping the image version breaks the assertion.
- **CI `kubectl wait` must exclude Job pods**: The `Verify pods are ready` step uses `--field-selector=status.phase=Running` to skip `Completed` Job pods (e.g., `kafka-topics`). A completed pod's `Ready` condition is `False` (container exited), so `kubectl wait --for=condition=ready` without this filter always times out.

## Key Directories

```
k8s-batch-rules-kie/src/main/java/com/cominotti/k8sbatch/
  batch/rulespoc/domain/      — TransactionRulesEvaluator port, FinancialTransaction, EnrichedFinancialTransaction, RiskScore, EnrichmentRuleConstants
  batch/rulespoc/adapters/evaluatingrules/         — TransactionFact (shared mutable fact)
  batch/rulespoc/adapters/evaluatingrules/dmn/     — DmnTransactionRulesEvaluator (Java + DMN hybrid)
  batch/rulespoc/adapters/evaluatingrules/droolsruleunit/ — DroolsRuleUnitTransactionRulesEvaluator, TransactionEnrichmentUnit

k8s-batch-jobs/src/main/java/com/cominotti/k8sbatch/
  batch/common/domain/    — CsvRecord, CsvRecordProcessor, BatchStepNames, BatchPartitionProperties
  batch/common/adapters/readingcsv/file/          — CsvRecordReaderFactory
  batch/common/adapters/persistingrecords/jdbc/   — CsvRecordWriter
  batch/common/adapters/observingexecution/logging/ — logging listeners, BatchDurationUtils
  batch/filerange/domain/ — FileRangePartitioner
  batch/filerange/config/ — FileRangeJobConfig
  batch/multifile/domain/ — MultiFilePartitioner
  batch/multifile/config/ — MultiFileJobConfig
  batch/transaction/domain/   — TransactionEnrichmentProcessor, TransactionJobProperties, TransactionTopicNames
  batch/transaction/adapters/streamingevents/kafka/           — TransactionKafkaConfig
  batch/transaction/adapters/persistingtransactions/jdbc/     — EnrichedTransactionWriter
  batch/transaction/config/   — TransactionEnrichmentJobConfig
  batch/rulespoc/adapters/evaluatingrules/drools/  — DroolsTransactionRulesEvaluator
  batch/rulespoc/adapters/evaluatingrules/evrete/  — EvreteTransactionRulesEvaluator
  batch/rulespoc/adapters/persistingresults/jdbc/  — EnrichedFinancialTransactionWriter
  batch/rulespoc/adapters/readingcsv/file/         — FinancialTransactionReaderFactory
  batch/rulespoc/config/      — RulesEnginePocJobConfig, RulesEngineProcessor, RulesEngineProperties
  config/             — RemotePartitioningBaseConfig, RemotePartitioningKafkaConfig, RemotePartitioningJmsConfig, StandaloneJobConfig
  web/adapters/launchingjobs/rest/ — JobController, HelloController
  web/config/         — AsyncJobOperatorConfig
  web/dto/            — JobExecutionResponse

k8s-batch-integration-tests/src/test/java/com/cominotti/k8sbatch/it/
  config/             — ContainerHolder, SharedContainersConfig, JmsContainersConfig, MysqlOnlyContainersConfig, BatchTestJobConfig
  rest/               — REST endpoint tests
  batch/standalone/   — standalone batch tests
  batch/remote/       — Kafka remote partitioning tests
  batch/jms/          — JMS remote partitioning tests (RabbitMQ via JMS client)
  database/           — schema verification tests
  infra/              — connectivity smoke tests

helm/k8s-batch/
  templates/
    app/              — Deployment, Service, HPA, Ingress, ConfigMap
    gateway/          — Deployment, Service, ConfigMap, HPA (gated on gateway.enabled)
    crud/             — Deployment, Service, ConfigMap, HPA (gated on crud.enabled)
    mysql/            — StatefulSet, Service, Secret, ConfigMap (Batch DDL)
    kafka/            — StatefulSet (KRaft), Services, topic init Job, Schema Registry Deployment+Service
  tests/              — helm-unittest YAML tests (*_test.yaml)

k8s-batch-crud/src/main/java/com/cominotti/k8sbatch/crud/
  domain/             — Customer, Account (JPA entities), CustomerService, AccountService, enums, EntityNotFoundException
  adapters/
    persistingcustomers/jpa/  — CustomerRepository (Spring Data JPA)
    persistingaccounts/jpa/   — AccountRepository (Spring Data JPA)
    managingcustomers/rest/   — CustomerController, dto/ (CreateCustomerRequest, CustomerResponse, etc.)
    managingaccounts/rest/    — AccountController, dto/ (CreateAccountRequest, AccountResponse, etc.)
    handlingerrors/rest/      — GlobalExceptionHandler (@RestControllerAdvice)
  config/             — JpaAuditingConfig

k8s-batch-e2e-tests/src/test/java/com/cominotti/k8sbatch/e2e/
  cluster/            — K3sClusterManager, DeploymentWaiter, HelmRenderer, PortForwardManager, PodUtils
  client/             — BatchAppClient (HTTP), CrudAppClient (HTTP), MysqlVerifier (JDBC)
  diagnostics/        — PodDiagnostics (failure dump)
  deploy/             — DeployHealthCheckE2E
  batch/              — FileRangeJobE2E, MultiFileJobE2E, StandaloneProfileE2E, PartitionDistributionE2E
  crud/               — CustomerCrudE2E

.github/workflows/    — CI pipelines (helm-validate.yml)

scripts/license/
  check-spdx.sh       — validates SPDX headers on all Java/shell files
  apply-spdx.sh       — auto-applies missing SPDX headers

config/checkstyle/
  javadoc-checks.xml   — Checkstyle rules for JavaDoc gap detection
  suppressions.xml     — exclusions for generated code and test method-level
```

## Claude Code Automations

- **`/helm-validate`** — runs helm lint, unittest, and template render
- **`/run-integration-tests`** — Docker + Helm prerequisite checks, Docker image build, then runs both integration tests and E2E tests
- **`/doc-review`** — reviews documentation quality (JavaDoc, comments, README) for changed files after code changes. Uses Checkstyle XML report as deterministic baseline, then applies AI-driven semantic review
- **`helm-reviewer`** subagent — reviews Helm changes against project conventions (invoked automatically during PR reviews)
- **`/spring-migration`** — reviews Java code for deprecated Spring, Spring Batch, Spring Boot, and Fabric8 APIs; checks imports, method calls, and configuration against current non-deprecated alternatives
- **`.mcp.json`** — shares context7 MCP server config with collaborators

## License

Apache-2.0. Every Java and shell source file must start with an SPDX header:

- **Java**: `// SPDX-License-Identifier: Apache-2.0` as the very first line
- **Shell**: `# SPDX-License-Identifier: Apache-2.0` (line 2 if shebang present, line 1 otherwise)

**Enforcement**: `check-spdx.sh` runs automatically during Maven's `validate` phase via `exec-maven-plugin`. Any `mvn compile`, `mvn test`, or `mvn verify` will fail if headers are missing.

**Auto-fix**: `mvn -Plicense-fix validate` or directly `./scripts/license/apply-spdx.sh`

## Checkstyle JavaDoc Validation

Deterministic JavaDoc gap detection via `maven-checkstyle-plugin` (Checkstyle 10.25.0). Runs in `validate` phase — no compilation needed.

**What it checks**: missing JavaDoc on public types/methods, missing `@param`/`@return`/`@throws` tags, empty tag descriptions, malformed JavaDoc, trivial summary sentences.

**What it skips**: Avro-generated classes (`generated-sources/`), method-level checks on test classes (only class-level JavaDoc required on tests).

**Configuration**: `config/checkstyle/javadoc-checks.xml` (checks) + `config/checkstyle/suppressions.xml` (exclusions).

**Build behavior**: Fails the build by default. Use `-Dcheckstyle.failOnViolation=false` for report-only mode, or `-Dskip.checkstyle=true` to skip entirely.

**XML report**: `<module>/target/checkstyle-javadoc.xml` per module — consumed by the `/doc-review` skill as a deterministic baseline.
