# k8s-batch

A reference project demonstrating **Spring Batch horizontal scaling on Kubernetes** using remote partitioning via Kafka.

## Architecture

```
              +--------------+--------------+
              |              |              |
         +----v----+   +----v----+   +----v----+
         |  Pod 1  |   |  Pod 2  |   |  Pod N  |    ← HPA scales these
         | Manager |   | Worker  |   | Worker  |
         | +Worker |   |         |   |         |
         +---------+   +---------+   +---------+
              |              |              |
     +--------v--------------v--------------v--------+
     |           Kafka (Confluent, KRaft mode)        |
     |        requests topic → worker pods            |
     +------------------------------------------------+
              |                           |
     +--------v-----------+   +-----------v-----------+
     |     MySQL 8.0      |   |   Schema Registry     |
     |  JobRepository +   |   |  (Confluent, stores   |
     |  Application data  |   |   in _schemas topic)  |
     +--------------------+   +-----------------------+
```

Every pod runs the same image and can act as both **manager** (partitions work) and **worker** (processes partitions). The manager uses **JobRepository polling** (not Kafka reply channels) to detect worker completion. MySQL provides distributed locking to prevent duplicate job execution.

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 4.0.3 |
| Spring Batch | 6.0.2 |
| MySQL | 8.0 |
| Kafka | Confluent Platform 7.9.0 (KRaft, no Zookeeper) |
| Schema Registry | Confluent 7.9.0 |
| Helm | 3 |
| Testcontainers | 2.0.3 |

## Sample Batch Jobs

| Job | Partitioning Strategy |
|-----|-----------------------|
| `fileRangeEtlJob` | Splits a single CSV by line ranges across workers |
| `multiFileEtlJob` | Assigns one CSV file per worker from a directory |

Both jobs: read CSV → process/validate → write to MySQL `target_records` table.

## Profiles

| Profile | Description |
|---------|-------------|
| `remote-partitioning` (default) | Partitions distributed via Kafka |
| `standalone` | In-process execution, no Kafka needed |

## REST Endpoints

| Endpoint | Description |
|----------|-------------|
| `POST /api/jobs/{jobName}` | Launch a batch job (async, returns HTTP 202) |
| `GET /api/jobs/{jobName}/executions/{id}` | Poll job execution status |
| `GET /hello` | Hello-world health check |
| `GET /actuator/health` | Actuator health |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for integration tests, E2E tests, and local development)
- Helm 3 + kubectl (for Kubernetes deployment and E2E tests)

## Build

```bash
# Compile
mvn clean compile

# Package (skip tests)
mvn package -DskipTests

# Full build with integration tests (requires Docker)
mvn verify -DskipE2E=true

# Full build with all tests including E2E
docker build -t k8s-batch:e2e .
mvn verify
```

## Local Development

```bash
# Start MySQL + Kafka + app
docker-compose up -d

# Check health
curl http://localhost:8080/hello
curl http://localhost:8080/actuator/health

# Standalone mode (no Kafka)
SPRING_PROFILES_ACTIVE=standalone docker-compose up -d app
```

## Integration Tests

Tests use Testcontainers to spin up MySQL and **Redpanda** (Kafka-compatible broker with built-in Schema Registry) automatically. Docker must be running.

```bash
# Run all integration tests
mvn -pl k8s-batch-integration-tests -am verify

# Run a specific test class
mvn -pl k8s-batch-integration-tests -am verify -Dit.test=FileRangePartitionStandaloneIT
```

### Test Categories (42 tests)

| Category | Tests | Description |
|----------|-------|-------------|
| REST Endpoints | 5 | Hello endpoint + Actuator health indicators |
| Batch Standalone | 12 | Both jobs without Kafka, recovery, skip policies |
| Batch Remote | 10 | Both jobs with real Kafka partitioning |
| Database | 7 | Batch schema, Flyway migrations, constraints |
| Infrastructure | 6 | MySQL/Kafka connectivity smoke tests |

### Container Startup Optimization

Container lifecycle is managed by `ContainerHolder` with decoupled startup:
- **Standalone tests** start only MySQL (via `MysqlOnlyContainersConfig` → `ContainerHolder.startMysqlOnly()`)
- **Remote tests** start MySQL + Redpanda in parallel (via `SharedContainersConfig` → `ContainerHolder.startAll()` using `Startables.deepStart()`)

### Timeout Layering

Tests are protected by multiple timeout layers:
- **Maven failsafe**: 5-minute process timeout (`forkedProcessTimeoutInSeconds: 300`)
- **JUnit `@Timeout`**: 120s for remote tests, 30s for standalone tests
- **Spring Batch partition timeout**: 15s in tests (production: 60s)
- **Kafka/JDBC/HTTP**: bounded timeouts on all external calls

## E2E Tests

E2E tests deploy the full Helm chart into a real **K3s** Kubernetes cluster (via Testcontainers) and validate the application end-to-end against **Confluent Kafka** and **Schema Registry**.

```bash
# Build Docker image first
docker build -t k8s-batch:e2e .

# Run E2E tests
mvn -pl k8s-batch-e2e-tests -am verify

# Skip E2E tests
mvn verify -DskipE2E=true
```

### E2E Test Suite (11 tests)

| Test Class | Tests | Description |
|------------|-------|-------------|
| `DeployHealthCheckE2E` | 3 | Pod readiness, actuator health, MySQL connectivity |
| `FileRangeJobE2E` | 2 | File-range job execution + worker step validation |
| `MultiFileJobE2E` | 2 | Multi-file job execution + one-worker-per-file validation |
| `StandaloneProfileE2E` | 3 | Standalone profile (no Kafka pods), both jobs |
| `PartitionDistributionE2E` | 1 | Verifies partitions distribute across workers |

### Cluster Lifecycle

The K3s cluster is started **once** and shared across all test classes — it is never restarted between tests. `K3sClusterManager` follows the same singleton pattern as `ContainerHolder` in integration tests:

- **K3s container**: started on first access, reused for the entire test suite
- **Docker images**: loaded into K3s once (`docker save` → `ctr images import`), tracked by a `loadedImages` set to avoid redundant loads
- **Helm deployments**: reused when the values file matches. Tests sharing the same profile (e.g., `e2e-remote.yaml`) skip redeployment entirely. A profile change (e.g., remote → standalone) triggers a teardown + redeploy
- **Test data isolation**: only application data is reset — `@BeforeEach cleanTestData()` deletes rows from `target_records` between test methods

This means the ~9 minute E2E runtime is dominated by one-time setup (K3s boot, image loading, initial deployment) rather than per-test overhead.

### Fast Failure Detection

The `DeploymentWaiter` provides intelligent pod readiness polling that fails fast instead of waiting for timeout:

- **Terminal errors** (ImagePullBackOff, CrashLoopBackOff, OOMKilled): detected and reported within seconds
- **Unschedulable pods**: detected immediately when K8s can't place a pod
- **Init container progress**: logged at each polling interval so you can see exactly where startup is blocked (e.g., "2/3 done | running=wait-for-kafka")
- **Progress stall detection**: if no pod makes forward progress for 120s, fails early with diagnostics
- **Container log streaming**: after 60s of a pod being not-ready with its main container running, streams container logs for immediate visibility into Spring Boot startup failures
- **Full diagnostics dump**: on any failure, `PodDiagnostics` dumps pod status (including init containers), container logs, and Kubernetes events

## Helm Chart

### Unit Tests (`helm-unittest`)

49 YAML-based tests validate template rendering: conditionals (HPA, Kafka, Schema Registry, init containers), values substitution, probes, replication factors, and security contexts.

```bash
# Install plugin (one-time)
helm plugin install https://github.com/helm-unittest/helm-unittest

# Run tests (~60ms)
helm unittest helm/k8s-batch
```

### Manifest Validation (`kubeconform`)

Validates rendered manifests against Kubernetes OpenAPI schemas:

```bash
helm template test-release helm/k8s-batch | kubeconform -strict -kubernetes-version 1.30.0
```

### CI Pipeline (GitHub Actions)

`.github/workflows/helm-validate.yml` runs three jobs:
1. **lint-and-unittest** — `helm lint` + `helm unittest`
2. **kubeconform** — schema validation against K8s 1.30
3. **k8s-smoke-test** — builds Docker image, deploys to K3s cluster via Helm, verifies health endpoint

## Kubernetes Deployment

### Helm Install

```bash
# Validate chart
helm lint helm/k8s-batch
helm template my-release helm/k8s-batch

# Deploy
helm install k8s-batch helm/k8s-batch \
  --set mysql.auth.rootPassword=<root-password> \
  --set mysql.auth.password=<app-password>

# Check status
kubectl get pods -l app.kubernetes.io/instance=k8s-batch
kubectl logs -l app.kubernetes.io/component=app -f

# Port-forward to access locally
kubectl port-forward svc/k8s-batch-k8s-batch 8080:8080
curl http://localhost:8080/hello
```

### Helm Configuration

Key `values.yaml` parameters:

| Parameter | Default | Description |
|-----------|---------|-------------|
| `app.replicaCount` | `2` | Initial pod replicas |
| `app.springProfile` | `remote-partitioning` | Active Spring profile |
| `hpa.enabled` | `true` | Enable HorizontalPodAutoscaler |
| `hpa.minReplicas` / `maxReplicas` | `2` / `10` | HPA scaling bounds |
| `mysql.auth.database` | `k8sbatch` | MySQL database name |
| `mysql.persistence.size` | `10Gi` | MySQL storage |
| `kafka.replicaCount` | `3` | Kafka broker count |
| `kafka.enabled` | `true` | Deploy Kafka |
| `schemaRegistry.enabled` | `true` | Deploy Schema Registry |
| `features.schemaRegistry` | `true` | Enable Schema Registry integration |
| `ingress.enabled` | `false` | Expose via Ingress |

### Standalone Mode (no Kafka)

```bash
helm install k8s-batch helm/k8s-batch \
  --set app.springProfile=standalone \
  --set kafka.enabled=false \
  --set features.kafka=false \
  --set mysql.auth.rootPassword=secret \
  --set mysql.auth.password=secret
```

## Project Structure

```
k8s-batch/
├── pom.xml                          # Parent POM
├── Dockerfile                       # Multi-stage build
├── docker-compose.yml               # Local dev stack
├── k8s-batch-app/                   # Main application
│   ├── pom.xml
│   └── src/main/
│       ├── java/.../k8sbatch/
│       │   ├── batch/               # Batch jobs, partitioners, readers/writers
│       │   ├── config/              # Kafka integration, remote partitioning
│       │   └── web/                 # REST controller
│       └── resources/
│           ├── application*.yml     # Config per profile
│           └── db/migration/        # Flyway SQL migrations
├── k8s-batch-integration-tests/     # Integration tests (Redpanda + MySQL)
│   ├── pom.xml
│   └── src/test/
│       ├── java/.../it/             # 13 test classes, 42 tests
│       │   └── config/              # ContainerHolder, SharedContainersConfig, etc.
│       └── resources/test-data/     # CSV fixtures
├── k8s-batch-e2e-tests/             # E2E tests (K3s + Confluent Kafka)
│   ├── pom.xml
│   └── src/test/
│       ├── java/.../e2e/            # 5 test classes, 11 tests
│       │   ├── cluster/             # K3sClusterManager, DeploymentWaiter, PodUtils
│       │   ├── client/              # BatchAppClient (HTTP), MysqlVerifier (JDBC)
│       │   └── diagnostics/         # PodDiagnostics (failure dump)
│       └── resources/
│           └── helm-values/         # E2E Helm values overrides
├── helm/k8s-batch/                  # Helm 3 chart
│   ├── Chart.yaml
│   ├── values.yaml
│   ├── tests/                       # helm-unittest YAML tests (49 tests)
│   └── templates/
│       ├── app/                     # Deployment, Service, HPA, Ingress
│       ├── mysql/                   # StatefulSet, init schema
│       └── kafka/                   # KRaft StatefulSet, Schema Registry, topic init Job
└── .github/workflows/               # CI pipelines
    └── helm-validate.yml            # Helm lint, unittest, kubeconform, K3s smoke test
```

## How Horizontal Scaling Works

1. A REST request (or scheduler) triggers a batch job on **any pod** — that pod becomes the **manager**
2. The manager's `Partitioner` splits work into N partitions
3. Partition requests are sent to Kafka topic `batch-partition-requests`
4. **All pods** (including the manager) consume partitions via Kafka consumer group
5. Each worker processes its partition independently
6. The manager **polls the JobRepository** (MySQL) to detect when all partitions are complete
7. MySQL `JobRepository` uses pessimistic locking to prevent duplicate job execution
8. HPA scales pods based on CPU/memory; new pods join the Kafka consumer group automatically

## License

Apache-2.0
