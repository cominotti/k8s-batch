---
name: spring-migration
description: "Review Java code for deprecated Spring, Spring Batch, Spring Boot, and related framework APIs. Checks imports, method calls, and configuration against current non-deprecated alternatives. Use after modifying Java files, adding dependencies, or upgrading framework versions."
disable-model-invocation: false
---

Review Java code for deprecated Spring ecosystem APIs and recommend migrations. Focus on compile-time `@Deprecated` annotations (especially `forRemoval = true`) and soft-deprecated patterns where the framework team recommends alternatives.

## How to Review

1. **Compile with deprecation warnings**: Run `mvn clean compile test-compile -Dskip.checkstyle=true 2>&1 | grep -E "warning.*deprecat|has been deprecated"` to find compiler-reported deprecations.
2. **Search imports and usages** in changed files for known deprecated patterns (see checklist below).
3. For each finding, report: file, line, deprecated API, replacement, and severity (`forRemoval` = HIGH, regular `@Deprecated` = MEDIUM, soft-deprecated/recommended-alternative = LOW).

## Spring Batch 6.x Deprecation Checklist

### HIGH — `forRemoval = true`

| Deprecated | Replacement | Notes |
|-----------|-------------|-------|
| `JobLauncher` interface | `JobOperator` | `JobOperator.start(Job, JobParameters)` returns `JobExecution` in SB6 |
| `TaskExecutorJobLauncher` | `TaskExecutorJobOperator` via `JobOperatorFactoryBean` | For async launches, set `taskExecutor` on the factory |
| `JobLauncherTestUtils` | `JobOperatorTestUtils(jobOperator, jobRepository)` | Call `startJob(params)` not `launchJob()` |
| Old `org.springframework.batch.item.*` packages | `org.springframework.batch.infrastructure.item.*` | All item interfaces moved |
| `org.springframework.batch.core.Job` (top-level) | `org.springframework.batch.core.job.Job` | Package restructured |
| Old `chunk(int, TransactionManager)` builder | `chunk(chunkSize).transactionManager(tm)` | Two-step builder API |

### MEDIUM — `@Deprecated` without `forRemoval`

| Deprecated | Replacement | Notes |
|-----------|-------------|-------|
| `JsonSerializer` / `JsonDeserializer` (Spring Kafka) | `JacksonJsonSerializer` / `JacksonJsonDeserializer` | Set `TRUSTED_PACKAGES` to `"*"` for remote partitioning |

### NOT deprecated (common false positives)

These are commonly assumed deprecated but are **not** — do not flag them:

| API | Status |
|-----|--------|
| `SimpleAsyncTaskExecutor(String prefix)` | Not deprecated (only no-arg constructor is deprecated in Spring 6.1) |
| `TaskExecutorPartitionHandler` | Not deprecated |
| `RemotePartitioningManagerStepBuilderFactory` | Not deprecated |
| `@StepScope` | Not deprecated |
| `Transformers.serializer()` / `deserializer()` (Spring Integration) | Not deprecated in SI 7.0 |
| `KafkaItemReaderBuilder` / `KafkaItemWriter` | Not deprecated |

## Spring Boot 4.x Deprecation Checklist

| Deprecated | Replacement | Notes |
|-----------|-------------|-------|
| `TestRestTemplate` | `RestClient` with `@LocalServerPort` | Removed in SB4 |
| `@DynamicPropertySource` for containers | `@ServiceConnection` | For MySQL; Kafka still uses `System.setProperty` |
| `spring.batch.initialize-schema` (no `jdbc` infix) | `spring.batch.jdbc.initialize-schema` | Old property removed in SB3 |

## Fabric8 Kubernetes Client 7.x

| Deprecated | Replacement | Notes |
|-----------|-------------|-------|
| `resource.createOrReplace()` | `resource.unlock().createOr(NonDeletingOperation::update)` | `unlock()` strips stale `resourceVersion` to prevent 409 Conflict |

## Testcontainers 2.x

| Deprecated | Replacement | Notes |
|-----------|-------------|-------|
| `org.testcontainers.containers.MySQLContainer` | `org.testcontainers.mysql.MySQLContainer` | New package, non-generic (no `<?>`) |

## Key Migration Patterns

### JobOperator as a @Bean (async REST launches)

`JobOperatorFactoryBean` implements `ApplicationContextAware` and `FactoryBean<JobOperator>`. When manually constructing it (not as a Spring-managed bean), you must call `setApplicationContext()` — otherwise `afterPropertiesSet()` fails with NPE during job auto-discovery.

**Preferred pattern**: define as a `@Bean` so Spring manages the lifecycle:

```java
@Configuration(proxyBeanMethods = false)
class AsyncJobOperatorConfig {
    @Bean("asyncJobOperator")
    JobOperatorFactoryBean asyncJobOperator(JobRepository jobRepository) {
        JobOperatorFactoryBean factory = new JobOperatorFactoryBean();
        factory.setJobRepository(jobRepository);
        factory.setTaskExecutor(new SimpleAsyncTaskExecutor("job-api-"));
        return factory;
    }
}
```

Inject with `@Qualifier("asyncJobOperator")` to avoid conflicting with the auto-configured synchronous `JobOperator`. The two coexist — Spring Boot's `@ConditionalOnMissingBean` does not suppress the auto-configured one when a named/qualified bean is defined separately.

### Fabric8 createOr pattern

```java
// Before (deprecated)
client.resource(obj).inNamespace(ns).createOrReplace();

// After
client.resource(obj).inNamespace(ns).unlock().createOr(NonDeletingOperation::update);
```

`unlock()` strips `resourceVersion` from the object metadata. Without it, updating a resource loaded from YAML (which may carry a stale `resourceVersion`) fails with 409 Conflict. For freshly-built resources (via builders), `resourceVersion` is null so `unlock()` is a no-op — but include it consistently.

## Output Format

For each file reviewed, produce a table:

```
| Line | Deprecated API | Replacement | Severity |
|------|---------------|-------------|----------|
| 13   | TaskExecutorJobLauncher | JobOperatorFactoryBean | HIGH (forRemoval) |
```

If no deprecations found, state: "No deprecated API usages detected."
