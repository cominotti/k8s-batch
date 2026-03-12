# CLAUDE.md — k8s-batch Project Guide

## Project Overview

Spring Boot 4.0.3 + Spring Batch 6.x reference project for horizontally-scalable batch processing on Kubernetes. Three batch jobs demonstrate different patterns: two CSV-to-DB ETL jobs use remote partitioning via Kafka (with standalone fallback), and a transaction enrichment job reads Avro events from Kafka, enriches them, and writes to both MySQL and a Kafka output topic.

## Tech Stack

- **Java 21**, **Spring Boot 4.0.3**, **Spring Batch 6.0.2**
- **MySQL 8.0** — JobRepository + application data
- **Kafka (Confluent Platform 7.9.0, KRaft mode)** — remote partitioning messaging + event streaming
- **Avro 1.12 + Confluent Schema Registry** — event serialization for transaction enrichment job
- **Helm 3** — Kubernetes deployment
- **Testcontainers 2.0.3** — integration tests
- **Flyway** — database migrations

## Prerequisites

- **JDK 21** (e.g., Temurin)
- **Docker** (not Podman — K3s E2E tests need privileged containers)
- **Maven 3.9+**
- **helm** CLI + `helm-unittest` plugin (`helm plugin install https://github.com/helm-unittest/helm-unittest`)

## Build & Test

```bash
mvn clean compile                              # compile all modules
mvn test-compile                               # compile including test sources
mvn -pl k8s-batch-integration-tests -am verify  # run integration tests (requires Docker)
mvn -pl k8s-batch-e2e-tests -am verify          # run E2E tests (requires Docker + helm CLI)
mvn verify -DskipE2E=true                      # run integration tests, skip E2E
mvn package -DskipTests                        # build JAR without tests
docker build -t k8s-batch:e2e .                # build Docker image for E2E tests
docker-compose up -d                           # local stack (app + MySQL + Kafka)
helm lint helm/k8s-batch                       # validate Helm chart
helm unittest helm/k8s-batch                   # run Helm unit tests (49 tests, ~60ms)
mvn validate                                   # verify Apache-2.0 SPDX headers + JavaDoc checks
mvn -Plicense-fix validate                     # auto-apply missing SPDX headers
mvn checkstyle:check                           # run JavaDoc checks only (standalone)
mvn checkstyle:check -Dcheckstyle.failOnViolation=false  # JavaDoc checks, report-only
mvn validate -Dskip.checkstyle=true            # skip JavaDoc checks entirely
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `k8s-batch-app` | Main Spring Boot application (REST, batch jobs, config) |
| `k8s-batch-integration-tests` | Testcontainers integration tests (separate to keep test infra out of production artifact) |
| `k8s-batch-e2e-tests` | E2E tests using Testcontainers K3s — deploys Helm chart into real K8s cluster |

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
- **Kafka bootstrap servers** are set via `System.setProperty` in `ContainerHolder` (Kafka `@ServiceConnection` requires `spring-boot-kafka` which conflicts with manual `RemotePartitioningJobConfig`)
- **Schema Registry URL** is set via `System.setProperty("spring.kafka.properties.schema.registry.url", REDPANDA.getSchemaRegistryAddress())` in `ContainerHolder.startAll()`
- **Container lifecycle** is managed by `ContainerHolder` (not directly in config classes):
  - `ContainerHolder.startMysqlOnly()` — standalone tests, skips Redpanda entirely
  - `ContainerHolder.startAll()` — parallel startup via `Startables.deepStart()` for remote tests
  - `SharedContainersConfig` delegates to `ContainerHolder.startAll()` + creates Kafka topics
  - `MysqlOnlyContainersConfig` delegates to `ContainerHolder.startMysqlOnly()`
- **Parallel startup**: use `Startables.deepStart(Stream.of(MYSQL, REDPANDA)).join()` — not sequential `.start()` calls

## Spring Boot 4.x Rules

- `TestRestTemplate` is removed. Use `RestClient` with `@LocalServerPort`.
- `spring-boot-starter-batch-jdbc` is required explicitly for database-backed `JobRepository`.
- `spring.batch.job.enabled: false` prevents auto-launching jobs at startup.
- **Auto-configuration modules extracted**: `spring-boot-flyway`, `spring-boot-integration`, `spring-boot-kafka` are separate dependencies in SB4 (not in `spring-boot-autoconfigure`).
- **Multi-module `@SpringBootTest`**: always use `classes = K8sBatchApplication.class` — Spring can't find it by package scanning across modules.
- **`spring-boot-maven-plugin` classifier**: use `<classifier>exec</classifier>` so dependent modules see the original JAR, not the fat JAR.

## Test Utilities

- **Use `JobOperatorTestUtils`** (not the deprecated `JobLauncherTestUtils`)
- Constructor: `new JobOperatorTestUtils(jobOperator, jobRepository)`
- Launch jobs with `startJob(params)` (not `launchJob`)
- **`JobRepositoryTestUtils`** is not deprecated — use for cleanup: `removeJobExecutions()`

## Profile Strategy

| Profile | Activation | Kafka Required | Description |
|---------|-----------|----------------|-------------|
| `remote-partitioning` | Default | Yes | Remote partitioning via Kafka |
| `standalone` | `--spring.profiles.active=standalone` | No | In-process `TaskExecutorPartitionHandler` |
| `integration-test` | Test classes only | Depends on test | Test-specific config (schema auto-create, debug logging) |

## Batch Job Design

### CSV ETL Jobs (fileRangeEtlJob, multiFileEtlJob)

Both CSV jobs follow the same pattern: **Partitioner → Manager Step → Worker Steps**

- `FileRangePartitioner` — splits a CSV by line ranges
- `MultiFilePartitioner` — assigns one CSV file per partition
- Manager step: `RemotePartitioningJobConfig` (Kafka) or `StandaloneJobConfig` (local threads)
- Worker step: `FlatFileItemReader` → `CsvRecordProcessor` → `JdbcBatchItemWriter`

### Transaction Enrichment Job (transactionEnrichmentJob)

Single chunk step (not partitioned) — reads Avro `TransactionEvent` from Kafka, enriches with exchange rate + risk score, writes to both MySQL and Kafka output topic via `CompositeItemWriter`.

- **Config**: `TransactionEnrichmentJobConfig` + `TransactionKafkaConfig` (both `@Profile("remote-partitioning")`)
- **Properties**: `TransactionJobProperties` bound to `batch.transaction.*`
- **Avro schemas**: `k8s-batch-app/src/main/avro/` (generates Java classes via `avro-maven-plugin`)
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
- **`AbstractE2ETest`** base class provides `@BeforeEach cleanTestData()`, `requiresKafka()` override, port-forward setup
- **Helm rendering**: `HelmRenderer` shells out to `helm template`; uses `redirectErrorStream(true)` to avoid deadlock
- **Rendered manifests are cached** in `K3sClusterManager` for reuse during teardown
- **E2E test data isolation**: When querying `BATCH_STEP_EXECUTION`, always scope by `JOB_EXECUTION_ID` — use `MysqlVerifier.countStepExecutionsForJob()`, not the unscoped query. Multiple test methods run the same job, and step executions accumulate across runs.

## Image and Version Constants Rule

**Never hardcode Docker image names, tags, or version numbers as string literals.** They must be defined in constants classes or values files and referenced by name.

| Scope | Where to define | Example constants |
|-------|----------------|-------------------|
| Integration tests | `TestContainerImages` (in `it/config/`) | `MYSQL_IMAGE`, `REDPANDA_IMAGE` |
| E2E tests | `E2EContainerImages` (in `e2e/`) | `APP_IMAGE`, `MYSQL_IMAGE`, `KAFKA_IMAGE`, `K3S_IMAGE`, `SCHEMA_REGISTRY_IMAGE` |
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
- **Profile levels**: production=INFO, standalone=INFO (`StandaloneJobConfig` at DEBUG), integration-test=DEBUG
- **Batch listeners**: `LoggingJobExecutionListener` and `LoggingStepExecutionListener` are `@Component` beans — register via `.listener()` on `JobBuilder` and `StepBuilder`/`RemotePartitioningManagerStepBuilder` respectively
- **Duration utility**: `BatchDurationUtils.between(start, end)` for null-safe `Duration.between()` — shared by both listeners
- **Log levels**: INFO for business events (job/step lifecycle, partition creation, config init), DEBUG for per-item details (filtered records, reader/writer creation), ERROR for failures, WARN for unexpected-but-non-fatal statuses

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
- **CI validation**: `.github/workflows/helm-validate.yml` runs helm lint, unittest, kubeconform, and K3s smoke test

## Key Directories

```
k8s-batch-app/src/main/java/com/cominotti/k8sbatch/
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
  config/             — RemotePartitioningJobConfig, StandaloneJobConfig
  web/adapters/launchingjobs/rest/ — JobController, HelloController
  web/config/         — AsyncJobOperatorConfig
  web/dto/            — JobExecutionResponse

k8s-batch-integration-tests/src/test/java/com/cominotti/k8sbatch/it/
  config/             — ContainerHolder, SharedContainersConfig, MysqlOnlyContainersConfig, BatchTestJobConfig
  rest/               — REST endpoint tests
  batch/standalone/   — standalone batch tests
  batch/remote/       — remote partitioning tests
  database/           — schema verification tests
  infra/              — connectivity smoke tests

helm/k8s-batch/
  templates/
    app/              — Deployment, Service, HPA, Ingress, ConfigMap
    mysql/            — StatefulSet, Service, Secret, ConfigMap (Batch DDL)
    kafka/            — StatefulSet (KRaft), Services, topic init Job, Schema Registry Deployment+Service
  tests/              — helm-unittest YAML tests (*_test.yaml)

k8s-batch-e2e-tests/src/test/java/com/cominotti/k8sbatch/e2e/
  cluster/            — K3sClusterManager, DeploymentWaiter, HelmRenderer, PortForwardManager, PodUtils
  client/             — BatchAppClient (HTTP), MysqlVerifier (JDBC)
  diagnostics/        — PodDiagnostics (failure dump)
  deploy/             — DeployHealthCheckE2E
  batch/              — FileRangeJobE2E, MultiFileJobE2E, StandaloneProfileE2E, PartitionDistributionE2E

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
- **`/run-integration-tests`** — Docker prerequisite check + `mvn verify` for integration tests
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
