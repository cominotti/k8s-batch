# CLAUDE.md — k8s-batch Project Guide

## Project Overview

Spring Boot 4.0.3 + Spring Batch 6.x reference project for horizontally-scalable batch processing on Kubernetes. Two CSV-to-DB ETL jobs demonstrate remote partitioning via Kafka and standalone (in-process) execution as fallback.

## Tech Stack

- **Java 21**, **Spring Boot 4.0.3**, **Spring Batch 6.0.2**
- **MySQL 8.0** — JobRepository + application data
- **Kafka 3.7 (KRaft)** — remote partitioning messaging
- **Helm 3** — Kubernetes deployment
- **Testcontainers 2.0.3** — integration tests
- **Flyway** — database migrations

## Build & Test

```bash
mvn clean compile                              # compile all modules
mvn test-compile                               # compile including test sources
mvn -pl k8s-batch-integration-tests -am verify  # run integration tests (requires Docker)
mvn package -DskipTests                        # build JAR without tests
docker-compose up -d                           # local stack (app + MySQL + Kafka)
helm lint helm/k8s-batch                       # validate Helm chart
```

## Module Structure

| Module | Purpose |
|--------|---------|
| `k8s-batch-app` | Main Spring Boot application (REST, batch jobs, config) |
| `k8s-batch-integration-tests` | Testcontainers integration tests (separate to keep test infra out of production artifact) |

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

Use `JacksonJsonSerializer` / `JacksonJsonDeserializer` (the old `JsonSerializer` / `JsonDeserializer` are deprecated).

## Testcontainers 2.x Rules

- **Artifact names** use `testcontainers-` prefix: `testcontainers-mysql`, `testcontainers-kafka`, `testcontainers-junit-jupiter`
- **MySQL**: `org.testcontainers.mysql.MySQLContainer` (not `org.testcontainers.containers.MySQLContainer`). **Non-generic** — no `<?>` wildcard.
- **Kafka**: `org.testcontainers.kafka.ConfluentKafkaContainer`
- **`@ServiceConnection`** handles connection wiring automatically — never use `@DynamicPropertySource`

## Spring Boot 4.x Rules

- `TestRestTemplate` is removed. Use `RestClient` with `@LocalServerPort`.
- `spring-boot-starter-batch-jdbc` is required explicitly for database-backed `JobRepository`.
- `spring.batch.job.enabled: false` prevents auto-launching jobs at startup.

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

Both jobs follow the same pattern: **Partitioner → Manager Step → Worker Steps**

- `FileRangePartitioner` — splits a CSV by line ranges
- `MultiFilePartitioner` — assigns one CSV file per partition
- Manager step: `RemotePartitioningJobConfig` (Kafka) or `StandaloneJobConfig` (local threads)
- Worker step: `FlatFileItemReader` → `CsvRecordProcessor` → `JdbcBatchItemWriter`
- `@StepScope` is mandatory on reader/processor/writer beans to enable partition-specific `ExecutionContext` injection

## Helm Chart Conventions

- Schema initialization: MySQL `docker-entrypoint-initdb.d` ConfigMap, not `initialize-schema: always`
- App Deployment omits `replicas` when HPA is enabled (prevents Helm/HPA conflicts)
- `checksum/config` annotation on Deployment triggers rolling restart on ConfigMap changes
- Init containers gate app startup until MySQL and Kafka are reachable
- Kafka runs in **KRaft mode** (no Zookeeper)

## Key Directories

```
k8s-batch-app/src/main/java/com/cominotti/k8sbatch/
  batch/common/       — shared data model, reader factory, writer, processor
  batch/filerange/    — file-range partitioning job
  batch/multifile/    — multi-file partitioning job
  batch/standalone/   — standalone (no Kafka) manager step config
  config/             — Kafka integration channels, remote partitioning config
  web/                — REST controller

k8s-batch-integration-tests/src/test/java/com/cominotti/k8sbatch/it/
  config/             — container configs, batch test config
  rest/               — REST endpoint tests
  batch/standalone/   — standalone batch tests
  batch/remote/       — remote partitioning tests
  database/           — schema verification tests
  infra/              — connectivity smoke tests

helm/k8s-batch/templates/
  app/                — Deployment, Service, HPA, Ingress, ConfigMap
  mysql/              — StatefulSet, Service, Secret, ConfigMap (Batch DDL)
  kafka/              — StatefulSet (KRaft), Services, topic init Job
```
