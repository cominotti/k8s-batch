# k8s-batch E2E Tests

End-to-end tests that deploy the full Helm chart into a real Kubernetes cluster (K3s inside Docker via Testcontainers) and verify batch job behavior through HTTP and JDBC connections.

Unlike the integration tests in `k8s-batch-integration-tests/` which test Spring Batch components in isolation with Testcontainers MySQL and Redpanda, these E2E tests exercise the **entire deployment pipeline**: Helm chart rendering, Kubernetes resource creation, init container sequencing, pod readiness, REST API job launching, Kafka partition distribution, and MySQL data verification.

## Prerequisites

- **JDK 21** (e.g., Temurin)
- **Docker** (not Podman — K3s needs privileged containers)
- **helm** CLI on PATH
- **Docker image built**: `docker build -t k8s-batch:e2e .` from the project root

## Running the Tests

```bash
# Build the app image first
docker build -t k8s-batch:e2e .

# Run E2E tests
mvn -pl k8s-batch-e2e-tests -am verify

# Skip E2E tests (e.g., during integration test runs)
mvn verify -DskipE2E=true
```

Tests are picked up by Maven Failsafe (`**/*E2E.java` pattern) and run in a single forked JVM (`forkCount=1, reuseForks=true`) with a 10-minute process timeout.

## Architecture Overview

```
┌─────────────────────────────────────────────────────────────────┐
│ Test JVM (Maven Failsafe)                                       │
│                                                                 │
│  AbstractE2ETest                                                │
│    ├── BatchAppClient ──── port-forward ──┐                     │
│    ├── MysqlVerifier ──── port-forward ──┐│                     │
│    └── KafkaEventSeeder (K8s Job) ───────┐│                     │
│                                          ││                     │
│  K3sClusterManager (singleton)           ││                     │
│    ├── K3sImageLoader                    ││                     │
│    ├── HelmRenderer                      ││                     │
│    ├── DeploymentWaiter                  ││                     │
│    └── PortForwardManager                ││                     │
│                                          ││                     │
│ ┌────────────────────────────────────────┼┼───────────────────┐ │
│ │ K3s Container (Testcontainers)         ││                   │ │
│ │                                        ▼▼                   │ │
│ │   ┌─────────┐  ┌───────┐  ┌───────┐  ┌──────────────────┐  │ │
│ │   │ App Pod │  │ MySQL │  │ Kafka │  │ Schema Registry  │  │ │
│ │   │ :8080   │  │ :3306 │  │ :9092 │  │ :8081            │  │ │
│ │   └─────────┘  └───────┘  └───────┘  └──────────────────┘  │ │
│ │                                                             │ │
│ │   ConfigMaps: e2e-test-data, e2e-test-data-multi            │ │
│ └─────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
```

## Cluster Lifecycle

**`K3sClusterManager`** is a singleton that manages the K3s cluster across all test classes in the JVM:

1. **First test class** calls `ensureClusterRunning()` → starts K3s container with 6 GB memory ceiling, builds Fabric8 client
2. **Image loading** via `K3sImageLoader` — parallel thread pool (max 4) or batch `docker save` for 3+ images (shared layers stored once)
3. **Helm deployment** via `deploy(valuesFile)` — renders chart with `helm template`, applies manifests via Fabric8, waits for pod readiness
4. **Profile switching** — if a different values file is requested, the current deployment is torn down first (manifests deleted, pods terminated) before redeploying
5. **Cluster reuse** — the K3s container persists across test classes; only port forwards are recreated per class

The `loadedImages` deduplication set prevents reloading the same Docker image multiple times — saving minutes when multiple test classes request the same images.

## Image Loading Pipeline

`K3sImageLoader` handles the `docker save → copyFileToContainer → ctr images import` pipeline:

| Strategy | When | How |
|----------|------|-----|
| **Batch save** | 3+ new images | Single `docker save img1 img2 img3 -o batch.tar` — shared layers (e.g., `eclipse-temurin:21-jre-alpine`) stored once, reducing I/O ~40% |
| **Parallel save** | 1-2 new images | Individual `docker save` per image in parallel threads (max 4) — each uses a unique container-side path `/tmp/image-<hash>.tar` to avoid collisions |

Images are defined in `E2EContainerImages` — never hardcoded in test classes:

| Constant | Image | Notes |
|----------|-------|-------|
| `APP_IMAGE` | `k8s-batch:e2e` | Locally built — `docker build -t k8s-batch:e2e .` |
| `MYSQL_IMAGE` | `mysql:8.0` | Must match `TestContainerImages.MYSQL_IMAGE` in IT module |
| `KAFKA_IMAGE` | `confluentinc/cp-kafka:7.9.0` | Only loaded when `requiresKafka()` returns true |
| `SCHEMA_REGISTRY_IMAGE` | `confluentinc/cp-schema-registry:7.9.0` | Only loaded when `requiresKafka()` returns true |
| `BUSYBOX_IMAGE` | `busybox:1.36` | Used by init containers |
| `K3S_IMAGE` | `rancher/k3s:v1.31.4-k3s1` | K3s cluster itself |

## Helm Chart Deployment

`HelmRenderer` shells out to `helm template` to render the chart, then `K3sClusterManager` applies the rendered YAML via Fabric8's `createOrUpdate`:

1. **Test data ConfigMaps** — created from classpath CSV files before Helm deployment:
   - `e2e-test-data`: `sample-10rows.csv`, `sample-100rows.csv` (mounted at `/data/test/`)
   - `e2e-test-data-multi`: `file-a.csv`, `file-b.csv`, `file-c.csv` (mounted at `/data/test/multi/`)
2. **Main manifests** — rendered without hooks, applied via `kubectl`-equivalent
3. **Hook manifests** — Kafka topic-creation Job rendered separately (we bypass Helm's hook lifecycle since we apply raw manifests, not `helm install`)
4. **Pod readiness** — `DeploymentWaiter` polls until all pods are ready or a failure is detected

Two Helm values files are used:
- **`e2e-remote.yaml`** — full stack: app, MySQL, Kafka (KRaft), Schema Registry
- **`e2e-standalone.yaml`** — minimal: app + MySQL only, no Kafka

## Pod Readiness and Failure Detection

`DeploymentWaiter` implements five detection features, polled every 5 seconds:

| # | Feature | Behavior |
|---|---------|----------|
| 1 | **Terminal errors** | ImagePullBackOff, CrashLoopBackOff, OOMKilled → **fail immediately** |
| 2 | **Unschedulable pods** | PodScheduled=False, Unschedulable → **fail immediately** (insufficient resources) |
| 3 | **Init progress logging** | Deduped progress like `"2/3 done \| running=wait-for-kafka"` |
| 4 | **Stall detection** | No new ready pods AND no init completions for 120s → **fail** |
| 5 | **Log streaming** | After 60s not-ready, tails last 50 lines from main container (once per pod) |

On any failure, `PodDiagnostics` dumps pod status, container logs (last 100 lines), and namespace events before throwing.

## Port Forwarding and Clients

`PortForwardManager` creates Fabric8 `LocalPortForward` instances to access pods from the test JVM:

- **App pod** (port 8080) → `BatchAppClient` — launches jobs via `POST /api/jobs/{name}`, polls status via `GET /api/jobs/{name}/executions/{id}`, checks `/actuator/health`
- **MySQL pod** (port 3306) → `MysqlVerifier` — verifies data in `target_records`, `enriched_transactions`, and Spring Batch metadata tables (`BATCH_STEP_EXECUTION`)

Both clients use no Spring dependencies — pure `java.net.http.HttpClient` and `java.sql.DriverManager`.

## Kafka Event Seeding

`KafkaEventSeeder` produces Avro events **from inside K3s** (not via port-forward) to avoid Kafka advertised-listener DNS issues:

1. Creates a ConfigMap with JSON-encoded events
2. Creates a K8s Job running `kafka-avro-console-producer` from the Schema Registry image
3. The producer pipes events from the ConfigMap into the Kafka topic with Avro schema validation
4. The Avro schema is derived from the generated `TransactionEvent` Java class to stay in sync with the `.avsc` definition

## Diagnostics

Two mechanisms provide failure diagnostics:

- **`E2EDiagnosticsExtension`** (JUnit 5 `TestWatcher`) — registered via `@ExtendWith` on `AbstractE2ETest`, automatically dumps all pod diagnostics when any test method fails
- **`PodDiagnostics`** — dumps three sections: pod status (phase, conditions, container states), pod logs (last 100 lines), and namespace events (sorted chronologically)

## Test Data

| File | Location | Rows | Used By |
|------|----------|------|---------|
| `sample-10rows.csv` | `/data/test/` | 10 | FileRangeJobE2E, StandaloneProfileE2E |
| `sample-100rows.csv` | `/data/test/` | 100 | FileRangeJobE2E, PartitionDistributionE2E |
| `file-a.csv` | `/data/test/multi/` | 30 | MultiFileJobE2E, StandaloneProfileE2E |
| `file-b.csv` | `/data/test/multi/` | 40 | MultiFileJobE2E, StandaloneProfileE2E |
| `file-c.csv` | `/data/test/multi/` | 50 | MultiFileJobE2E, StandaloneProfileE2E |

Test data is cleaned **before** each test method (not after) so that failing tests leave their data visible for post-mortem analysis.

## Test Classes

| Class | Profile | What It Validates |
|-------|---------|-------------------|
| `DeployHealthCheckE2E` | remote | All pods ready, actuator health UP, MySQL accessible |
| `FileRangeJobE2E` | remote | CSV ETL via Kafka remote partitioning (10 rows, 100 rows with multi-partition) |
| `MultiFileJobE2E` | remote | Multi-file ETL (3 files, 120 rows total), one worker step per file |
| `PartitionDistributionE2E` | remote | Manager/worker step metadata in BATCH_STEP_EXECUTION, total READ_COUNT = 100 |
| `TransactionEnrichmentJobE2E` | remote | Avro events → Kafka → enrichment → MySQL (5 events end-to-end) |
| `StandaloneProfileE2E` | standalone | No Kafka pods deployed, file-range and multi-file jobs work with local partitioning |

## Resource Configuration

E2E Helm values use slim resource limits — these pods process tiny test datasets, not production traffic:

| Component | Request | Limit |
|-----------|---------|-------|
| App | 100m / 256Mi | 1 CPU / 384Mi |
| MySQL | 100m / 256Mi | 500m / 512Mi |
| Kafka | 100m / 256Mi | 500m / 768Mi |
| Schema Registry | 50m / 128Mi | 250m / 256Mi |

The K3s container has a **6 GB memory ceiling** — resource exhaustion surfaces as pod-level OOMKilled (detected by `DeploymentWaiter`) rather than an opaque Docker daemon kill at the host level.

## Adding a New Test

1. **Create the test class** extending `AbstractE2ETest`:
   ```java
   class MyNewJobE2E extends AbstractE2ETest {
       @Override
       protected String valuesFile() {
           return "e2e-remote.yaml"; // or "e2e-standalone.yaml"
       }

       @Override
       protected boolean requiresKafka() {
           return true; // if your test needs Kafka
       }

       @Test
       void shouldDoSomething() throws Exception {
           JobResponse result = appClient.launchJobAndWaitForCompletion(
                   "myJobName", Map.of("param", "value"), Duration.ofMinutes(3));
           assertThat(result.status()).isEqualTo("COMPLETED");
       }
   }
   ```

2. **If you need new test data**: add CSV files to `src/test/resources/test-data/csv/` and register them in `K3sClusterManager.createTestDataConfigMaps()`

3. **If you need new images**: add constants to `E2EContainerImages` and override `requiredImages()` in your test class

4. **If you need new Kafka topics**: add them to the `kafka.topics` section in `e2e-remote.yaml`

5. **Name your class `*E2E.java`** — Maven Failsafe's include pattern picks up `**/*E2E.java`
