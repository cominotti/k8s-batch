# Plan: Spring Boot 4.0.3 + Spring Batch + K8s Reference Project

## Context

Create a **greenfield Maven-based reference project** demonstrating how to combine Spring Batch with Kubernetes for horizontally-scalable batch processing. The project deploys via Helm 3 charts with MySQL (JobRepository + app data) and Kafka (remote partitioning messaging). It includes two CSV-to-DB ETL sample jobs, REST/Actuator endpoints, and extensive Testcontainers integration tests.

**Tech stack**: Java 21, Spring Boot 4.0.3, Spring Batch 6.x, Spring Integration Kafka, MySQL 8.0, Kafka (KRaft), Helm 3, Testcontainers 2.x.

---

## High-Level Architecture

```
                    +-------------------+
                    |     Ingress       |
                    | (optional, REST)  |
                    +--------+----------+
                             |
              +--------------+--------------+
              |              |              |
         +----v----+   +----v----+   +----v----+
         |  Pod 1  |   |  Pod 2  |   |  Pod N  |    <-- HPA scales these
         | Manager |   | Worker  |   | Worker  |
         | +Worker |   |         |   |         |
         +---------+   +---------+   +---------+
              |              |              |
     +--------v--------------v--------------v--------+
     |                   Kafka                        |
     |  requests topic  <-->  replies topic           |
     +------------------------------------------------+
              |
     +--------v-----------------------------------------+
     |                   MySQL                           |
     |  Spring Batch schema + Application tables         |
     +---------------------------------------------------+
```

Each pod runs the same image. Any pod can act as **manager** (partitions work, sends via Kafka) or **worker** (consumes partitions from Kafka). MySQL JobRepository provides distributed locking to prevent duplicate job execution. HPA scales pods based on CPU/memory.

---

## 1. Project Structure

```
k8s-batch/
├── pom.xml                              # Parent POM
├── Dockerfile                           # Multi-stage (Maven build → JRE 21 runtime)
├── .dockerignore
├── docker-compose.yml                   # Local dev: app + MySQL + Kafka (KRaft)
├── k8s-batch-jobs/
│   ├── pom.xml                          # Main app module
│   └── src/main/
│       ├── java/com/cominotti/k8sbatch/
│       │   ├── K8sBatchApplication.java
│       │   ├── web/
│       │   │   └── HelloController.java             # GET /hello
│       │   ├── batch/
│       │   │   ├── common/
│       │   │   │   ├── CsvRecord.java               # CSV data model
│       │   │   │   ├── CsvRecordProcessor.java      # ItemProcessor
│       │   │   │   └── CsvRecordWriter.java         # JdbcBatchItemWriter
│       │   │   ├── filerange/
│       │   │   │   ├── FileRangePartitioner.java    # Partitions by line ranges
│       │   │   │   ├── FileRangeJobConfig.java      # Job + manager step
│       │   │   │   └── FileRangeWorkerConfig.java   # Worker step
│       │   │   ├── multifile/
│       │   │   │   ├── MultiFilePartitioner.java    # Partitions by file
│       │   │   │   ├── MultiFileJobConfig.java      # Job + manager step
│       │   │   │   └── MultiFileWorkerConfig.java   # Worker step
│       │   │   └── standalone/
│       │   │       └── StandaloneJobConfig.java     # Profile: standalone (no Kafka)
│       │   └── config/
│       │       ├── KafkaIntegrationConfig.java      # Spring Integration Kafka channels
│       │       └── DataSourceConfig.java            # MySQL DataSource
│       └── resources/
│           ├── application.yml                      # Base config
│           ├── application-remote-partitioning.yml  # Kafka-backed partitioning
│           ├── application-standalone.yml           # Direct execution, no Kafka
│           └── db/migration/                        # Flyway migrations
│               └── V1__create_target_table.sql      # App data table
├── k8s-batch-integration-tests/
│   ├── pom.xml                          # Test module (Testcontainers, Spring Batch Test)
│   └── src/test/
│       ├── java/com/cominotti/k8sbatch/it/
│       │   ├── config/
│       │   │   ├── SharedContainersConfig.java      # Singleton MySQL + Kafka containers
│       │   │   ├── MysqlOnlyContainersConfig.java   # For standalone tests
│       │   │   └── BatchTestJobConfig.java          # JobLauncherTestUtils per job
│       │   ├── AbstractIntegrationTest.java         # Base: @SpringBootTest + containers
│       │   ├── AbstractBatchIntegrationTest.java    # Base: + JobLauncherTestUtils + cleanup
│       │   ├── AbstractStandaloneBatchTest.java     # Base: standalone profile
│       │   ├── rest/
│       │   │   ├── HelloEndpointIT.java             # 2 tests
│       │   │   └── ActuatorHealthIT.java            # 4 tests
│       │   ├── batch/
│       │   │   ├── standalone/
│       │   │   │   ├── FileRangePartitionStandaloneIT.java   # 5 tests
│       │   │   │   ├── MultiFilePartitionStandaloneIT.java   # 4 tests
│       │   │   │   └── JobRecoveryStandaloneIT.java          # 3 tests
│       │   │   └── remote/
│       │   │       ├── FileRangePartitionRemoteIT.java       # 4 tests
│       │   │       ├── MultiFilePartitionRemoteIT.java       # 3 tests
│       │   │       └── PartitionDistributionIT.java          # 3 tests
│       │   ├── database/
│       │   │   ├── BatchSchemaIT.java               # 4 tests
│       │   │   └── AppSchemaIT.java                 # 3 tests
│       │   └── infra/
│       │       ├── MysqlConnectivityIT.java         # 3 tests
│       │       └── KafkaConnectivityIT.java         # 3 tests
│       └── resources/
│           ├── application-integration-test.yml
│           └── test-data/csv/
│               ├── single/
│               │   ├── sample-100rows.csv
│               │   ├── sample-10rows.csv
│               │   └── sample-with-errors.csv
│               ├── multi/
│               │   ├── file-a.csv  (30 rows)
│               │   ├── file-b.csv  (40 rows)
│               │   └── file-c.csv  (50 rows)
│               └── empty/
└── helm/
    └── k8s-batch/
        ├── Chart.yaml
        ├── .helmignore
        ├── values.yaml
        └── templates/
            ├── _helpers.tpl
            ├── NOTES.txt
            ├── app/
            │   ├── deployment.yaml
            │   ├── service.yaml
            │   ├── serviceaccount.yaml
            │   ├── configmap.yaml
            │   ├── secret.yaml
            │   ├── hpa.yaml
            │   └── ingress.yaml
            ├── mysql/
            │   ├── statefulset.yaml
            │   ├── service.yaml
            │   ├── secret.yaml
            │   └── configmap.yaml          # Spring Batch DDL init scripts
            └── kafka/
                ├── statefulset.yaml         # KRaft mode, no Zookeeper
                ├── service.yaml
                ├── service-headless.yaml
                └── job-create-topics.yaml   # Helm hook for topic creation
```

---

## 2. Maven Configuration

### Parent POM (`pom.xml`)
- `spring-boot-starter-parent:4.0.3` as parent
- Modules: `k8s-batch-jobs`, `k8s-batch-integration-tests`
- Java 21 compiler settings
- Managed dependency versions for Spring Batch, Spring Integration, Testcontainers

### App Module (`k8s-batch-jobs/pom.xml`)
Key dependencies:
- `spring-boot-starter-web` — REST endpoints
- `spring-boot-starter-actuator` — health/metrics
- `spring-boot-starter-batch` — batch framework
- `spring-boot-starter-batch-jdbc` — **required in Spring Boot 4** for DB-backed JobRepository
- `spring-batch-integration` — remote partitioning support
- `spring-integration-kafka` — Kafka channel adapters
- `spring-kafka` — Kafka auto-configuration
- `mysql-connector-j` — MySQL driver
- `flyway-core` + `flyway-mysql` — schema migrations

### Integration Tests Module (`k8s-batch-integration-tests/pom.xml`)
Key dependencies:
- `k8s-batch-jobs` (scope: test)
- `spring-boot-starter-test`
- `spring-boot-testcontainers` — `@ServiceConnection` support
- `org.testcontainers:mysql`
- `org.testcontainers:kafka`
- `spring-batch-test` — `JobLauncherTestUtils`, `JobRepositoryTestUtils`
- `awaitility` — async assertions for Kafka tests
- Maven Failsafe plugin (`*IT.java` pattern, `forkCount=1`, `reuseForks=true`)

---

## 3. Spring Application Design

### Profiles
| Profile | Purpose | Kafka Required |
|---------|---------|----------------|
| `remote-partitioning` (default) | Remote partitioning via Kafka | Yes |
| `standalone` | Direct execution via `TaskExecutorPartitionHandler` | No |
| `integration-test` | Test-specific config (batch schema auto-create, logging) | Depends |

### Batch Jobs

**Job 1: File-Range Partitioning** (`fileRangeEtlJob`)
- `FileRangePartitioner`: Reads CSV line count, creates N partitions with `startLine`/`endLine` in ExecutionContext
- Manager step: Uses `RemotePartitioningManagerStepBuilderFactory` → Kafka outbound channel
- Worker step: `FlatFileItemReader` configured with line range → `CsvRecordProcessor` → `JdbcBatchItemWriter`
- Chunk size: 100

**Job 2: Multi-File Partitioning** (`multiFileEtlJob`)
- `MultiFilePartitioner`: Scans directory, creates one partition per file with `filePath` in ExecutionContext
- Same manager/worker pattern, workers read their assigned file entirely

**Standalone fallback** (profile: `standalone`):
- Same Partitioner classes, but uses `TaskExecutorPartitionHandler` with a local `SimpleAsyncTaskExecutor`
- No Kafka channels — partitions execute in-process on threads

### Kafka Integration Channels (profile: `remote-partitioning`)
```
Manager → outboundRequests (DirectChannel) → Spring Integration KafkaProducerMessageHandler
                                                  → topic: batch-partition-requests
Workers ← inboundRequests (QueueChannel) ← Spring Integration KafkaMessageDrivenChannelAdapter
                                                  ← topic: batch-partition-requests

Workers → outboundReplies (DirectChannel) → KafkaProducerMessageHandler
                                                  → topic: batch-partition-replies
Manager ← inboundReplies (QueueChannel) ← KafkaMessageDrivenChannelAdapter
                                                  ← topic: batch-partition-replies
```

### Data Model
CSV format: `id,name,email,amount,date`

Target MySQL table (`target_records`):
```sql
CREATE TABLE target_records (
    id BIGINT PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    email VARCHAR(255),
    amount DECIMAL(10,2),
    record_date DATE,
    source_file VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

### REST Endpoints
- `GET /hello` → `{"message": "Hello from k8s-batch!"}`
- `GET /actuator/health` → standard Actuator with DB + Kafka health indicators
- `GET /actuator/health/liveness` → K8s liveness probe
- `GET /actuator/health/readiness` → K8s readiness probe

---

## 4. Dockerfile (Multi-Stage)

- **Stage 1 (build)**: `maven:3.9-eclipse-temurin-21` — build JAR, extract Spring Boot layers
- **Stage 2 (runtime)**: `eclipse-temurin:21-jre-alpine` — copy layers in cache-optimal order
- Non-root user (`appuser`)
- `HEALTHCHECK` via wget to `/actuator/health`
- JVM flags: `-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0`

---

## 5. Helm Chart Design

### Key values.yaml Parameters
- `app.replicaCount: 2`, `app.springProfile: remote-partitioning`
- `app.config.batchInitializeSchema: never` (MySQL init scripts handle schema)
- `mysql.auth.{database,username,password}`, `mysql.persistence.size: 10Gi`
- `kafka.replicaCount: 3`, KRaft mode (no Zookeeper)
- `kafka.topics.partitionRequests/partitionReplies` — auto-created via Helm hook Job
- `hpa.enabled: true`, min 2, max 10, CPU target 70%
- `features.kafka: true` — toggle Kafka StatefulSet
- `ingress.enabled: false` — optional

### Schema Initialization Strategy
MySQL ConfigMap at `/docker-entrypoint-initdb.d/01-spring-batch-schema.sql` creates all `BATCH_*` tables on first boot. App sets `initialize-schema: never`. App Deployment has init containers that wait for MySQL/Kafka readiness before starting.

### Horizontal Scaling
- App Deployment + HPA. Conservative scale-down (5-min window, 1 pod at a time) to avoid killing in-flight batch work.
- Kafka consumer groups distribute partitions across worker pods.
- MySQL pessimistic locking prevents duplicate job execution.

---

## 6. Integration Tests (41 tests across 12 classes)

### Container Strategy
Singleton containers (MySQL + Kafka) shared across all test classes via static initialization in `SharedContainersConfig`. Uses `@ServiceConnection` for auto-wiring (no `@DynamicPropertySource` needed). Single JVM fork via Failsafe plugin.

### Test Categories
| Category | Classes | Tests | Description |
|----------|---------|-------|-------------|
| REST Endpoints | 2 | 6 | Hello endpoint + Actuator health with DB/Kafka indicators |
| Batch Standalone | 3 | 12 | Both jobs without Kafka, plus restart/recovery and skip policies |
| Batch Remote | 3 | 10 | Both jobs with real Kafka, partition distribution verification |
| Database | 2 | 7 | Batch schema tables, Flyway migrations, constraints |
| Infrastructure | 2 | 6 | MySQL/Kafka connectivity smoke tests |

### Key Test Details
- **Standalone tests**: Use `@ActiveProfiles({"integration-test", "standalone"})`, only MySQL container
- **Remote tests**: Use real Kafka messaging in same JVM, `Awaitility` for async assertions
- **Cleanup**: `JobRepositoryTestUtils.removeJobExecutions()` + `DELETE FROM target_records` after each test
- **No `@Transactional`** on batch tests (batch jobs run in their own transactions)

---

## 7. Implementation Sequence

### Phase 0: Documentation
1. Create `docs/plans/` directory and persist this full architecture plan there

### Phase 1: Project Foundation (~20 files)
2. Git init + `.gitignore`
2. Parent POM + module POMs
3. `K8sBatchApplication.java`
4. `application.yml` + profile variants
5. `HelloController.java`
6. Flyway migration `V1__create_target_table.sql`

### Phase 2: Batch Core (~10 files)
7. `CsvRecord.java`, `CsvRecordProcessor.java`, `CsvRecordWriter.java`
8. `FileRangePartitioner.java` + `FileRangeJobConfig.java` + `FileRangeWorkerConfig.java`
9. `MultiFilePartitioner.java` + `MultiFileJobConfig.java` + `MultiFileWorkerConfig.java`
10. `StandaloneJobConfig.java` (standalone profile)

### Phase 3: Kafka Integration (~3 files)
11. `KafkaIntegrationConfig.java` (channels, adapters)
12. `application-remote-partitioning.yml`

### Phase 4: Docker + Compose (~3 files)
13. `Dockerfile` + `.dockerignore`
14. `docker-compose.yml`

### Phase 5: Helm Charts (~21 files)
15. Chart.yaml, values.yaml, _helpers.tpl
16. MySQL templates (StatefulSet, Service, Secret, ConfigMap with Batch DDL)
17. Kafka templates (StatefulSet KRaft, Services, topic init Job)
18. App templates (Deployment, Service, HPA, Ingress, ConfigMap, Secret, ServiceAccount)

### Phase 6: Integration Tests (~25 files)
19. Test module POM
20. Container configs + base test classes
21. REST endpoint tests
22. Standalone batch tests
23. Remote partitioning batch tests
24. Database + infrastructure tests
25. Test data CSV fixtures

---

## 8. Verification

### Local Development
```bash
# Build the project
mvn clean package -DskipTests

# Start local stack
docker-compose up -d

# Verify endpoints
curl http://localhost:8080/hello
curl http://localhost:8080/actuator/health
```

### Integration Tests
```bash
# Run all integration tests (requires Docker)
mvn -pl k8s-batch-integration-tests verify

# Run specific category
mvn -pl k8s-batch-integration-tests verify -Dit.test="*StandaloneIT"
```

### Helm Chart
```bash
# Lint and template
helm lint helm/k8s-batch
helm template my-release helm/k8s-batch

# Deploy to K8s cluster
helm install k8s-batch helm/k8s-batch \
  --set mysql.auth.rootPassword=secret \
  --set mysql.auth.password=secret

# Verify pods
kubectl get pods -l app.kubernetes.io/name=k8s-batch
kubectl logs -l app.kubernetes.io/component=app -f
```
