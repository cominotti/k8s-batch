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
     |                   Kafka (KRaft)                |
     |  requests topic  ←→  replies topic             |
     +------------------------------------------------+
              |
     +--------v-----------------------------------------+
     |                   MySQL 8.0                       |
     |  Spring Batch JobRepository + Application tables  |
     +---------------------------------------------------+
```

Every pod runs the same image and can act as both **manager** (partitions work) and **worker** (processes partitions). MySQL JobRepository provides distributed locking to prevent duplicate job execution.

## Tech Stack

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 4.0.3 |
| Spring Batch | 6.0.2 |
| MySQL | 8.0 |
| Kafka | 3.7 (KRaft, no Zookeeper) |
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
| `GET /hello` | Hello-world health check |
| `GET /actuator/health` | Actuator health (DB + Kafka indicators) |
| `GET /actuator/health/liveness` | Kubernetes liveness probe |
| `GET /actuator/health/readiness` | Kubernetes readiness probe |

## Prerequisites

- Java 21+
- Maven 3.9+
- Docker (for integration tests and local development)
- Helm 3 + kubectl (for Kubernetes deployment)

## Build

```bash
# Compile
mvn clean compile

# Package (skip tests)
mvn package -DskipTests

# Full build with integration tests (requires Docker)
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

Tests use Testcontainers to spin up MySQL and Kafka automatically. Docker must be running.

```bash
# Run all integration tests
mvn -pl k8s-batch-integration-tests -am verify

# Run a specific test class
mvn -pl k8s-batch-integration-tests -am verify -Dit.test=FileRangePartitionStandaloneIT

# Skip integration tests
mvn install -DskipITs
```

### Test Categories (40 tests)

| Category | Tests | Description |
|----------|-------|-------------|
| REST Endpoints | 5 | Hello endpoint + Actuator health indicators |
| Batch Standalone | 12 | Both jobs without Kafka, recovery, skip policies |
| Batch Remote | 10 | Both jobs with real Kafka partitioning |
| Database | 7 | Batch schema, Flyway migrations, constraints |
| Infrastructure | 6 | MySQL/Kafka connectivity smoke tests |

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
├── k8s-batch-integration-tests/     # Integration tests
│   ├── pom.xml
│   └── src/test/
│       ├── java/.../it/             # 12 test classes
│       └── resources/test-data/     # CSV fixtures
└── helm/k8s-batch/                  # Helm 3 chart
    ├── Chart.yaml
    ├── values.yaml
    └── templates/
        ├── app/                     # Deployment, Service, HPA, Ingress
        ├── mysql/                   # StatefulSet, init schema
        └── kafka/                   # KRaft StatefulSet, topic init Job
```

## How Horizontal Scaling Works

1. A REST request (or scheduler) triggers a batch job on **any pod** — that pod becomes the **manager**
2. The manager's `Partitioner` splits work into N partitions
3. Partition requests are sent to Kafka topic `batch-partition-requests`
4. **All pods** (including the manager) consume partitions via Kafka consumer group
5. Each worker processes its partition and sends a reply to `batch-partition-replies`
6. The manager collects replies and marks the job complete
7. MySQL `JobRepository` uses pessimistic locking to prevent duplicate job execution
8. HPA scales pods based on CPU/memory; new pods join the Kafka consumer group automatically

## License

MIT
