---
name: doc-review
description: "Review documentation quality (JavaDoc, inline comments, README) after any code change, refactoring, new feature, bug fix, or architecture modification. Checks for stale, missing, unclear, or incomplete documentation. Use this skill whenever Java files are created or modified, after implementing features, fixing bugs, or refactoring code. Also use when the user asks to check, audit, or improve documentation quality."
disable-model-invocation: false
---

Review documentation quality for changed files. The target audience is developers with little or no Spring Batch experience — documentation must be friendly, explanatory, and never assume familiarity with Spring Batch internals.

## Step 0: Run Checkstyle JavaDoc Analysis (Deterministic Baseline)

Before AI-driven review, run Checkstyle to get a deterministic list of structural JavaDoc gaps.

1. Run `mvn checkstyle:check -Dcheckstyle.failOnViolation=false` (report-only — don't fail on violations during review)
2. Read the XML reports at `<module>/target/checkstyle-javadoc.xml` for each module (e.g., `k8s-batch-jobs/target/checkstyle-javadoc.xml`)
3. Parse `<file>` and `<error>` elements — each error has `line`, `column`, `severity`, `message`, and `source` (the check class name)
4. Map each Checkstyle violation to the appropriate severity for the final report:
   - `MissingJavadocType` / `MissingJavadocMethod` → **[MISSING]**
   - `JavadocMethod` (missing @param/@return/@throws) → **[MISSING]**
   - `NonEmptyAtclauseDescription` → **[UNCLEAR]**
   - `JavadocStyle` / `SummaryJavadoc` → **[IMPROVEMENT]**
5. Include all violations in the report under the appropriate file. These are confirmed structural gaps — do not second-guess them. Then proceed with Steps 1-5 for semantic review.

**If Checkstyle is not available** (e.g., `mvn` not on PATH, or the project hasn't adopted it yet), skip this step and proceed with AI-only review.

## Step 1: Identify Changed Files

Determine which files changed. Try these in order until one works:

1. `git diff --name-only` (unstaged changes)
2. `git diff --name-only --cached` (staged changes)
3. `git diff --name-only HEAD~1` (last commit)
4. `git status --porcelain` (fallback)

Filter to relevant files:
- **Java files** (`*.java`) — check JavaDoc, inline comments, Spring Batch explanations
- **README.md** / **CLAUDE.md** — check if code changes require updates
- **YAML config files** (`application*.yml`) — check if new properties need documentation
- **Helm templates/values** — check if chart changes need annotation

If no relevant files changed, report "No documentation-relevant changes detected" and stop.

## Step 2: Review Each Changed Java File

Read each changed Java file in full and check ALL of the following.

### 2a. Class-Level JavaDoc (REQUIRED on every class, interface, enum, and record)

Every class must have JavaDoc that explains:
- The class's **purpose** and **role in the architecture**
- Any Spring Batch concept the class uses or implements (see glossary in Step 4)
- For `@Configuration` classes: what profile activates it and what beans it creates
- For test classes: what is being tested, what infrastructure is needed, and any isolation strategy

Good example (from this project's FileRangePartitioner):
```java
/**
 * Splits a single CSV file into line-range partitions for parallel processing.
 *
 * <p>Implements the Spring Batch {@link Partitioner} contract: {@link #partition(int)} returns a
 * map of {@link ExecutionContext} objects, one per partition. Each context carries the keys
 * {@code startLine}, {@code endLine}, and {@code resourcePath} — these travel over Kafka (remote
 * mode) or are passed in-memory (standalone mode), and are injected into worker step beans via
 * {@code @Value("#{stepExecutionContext['...']}}")} in {@code FileRangeJobConfig}.
 */
```

Bad example:
```java
/** Partitioner for files. */
```

The good example explains what the class does, which Spring Batch contract it implements, what data it produces, and how that data flows through the system. The bad example tells the reader nothing they couldn't infer from the class name.

### 2b. Method-Level JavaDoc

- **Required** on all `public` methods (except simple getters/setters/toString/record accessors)
- **Required** on `@Bean` methods in `@Configuration` classes when the bean's purpose or wiring is not obvious from the method name alone
- `@param` tags: required when the parameter's meaning or constraints are not obvious from its name and type (e.g., `gridSize` in a partitioner deserves explanation because it's a "hint", not a hard count)
- `@return` tag: required when the return value's meaning, structure, or edge cases are not obvious
- `@throws` tag: required for all checked exceptions

### 2c. Field-Level JavaDoc

- **Required** on `public static final` constants — explain what the value represents and where it is used
- **Required** on `private` fields injected via `@Value` with non-obvious SpEL expressions
- Records: field-level JavaDoc via `@param` tags in the class-level JavaDoc when field meaning is non-obvious

### 2d. Inline Comments

- **Required** on non-obvious code: SpEL expressions, Kafka configuration properties, serialization choices, concurrency patterns, type casts with a reason
- **Required** when code depends on a contract defined elsewhere (e.g., "key names must match @Value expressions in FileRangeJobConfig")
- Must explain **why**, not **what** — avoid comments that merely restate the code
- Comments mentioning Spring Batch concepts must explain them for newcomers (see glossary)
- Must not be stale — if code changed, verify surrounding comments still match

Good inline comment:
```java
// NullChannel discards the handler result — the manager detects completion by
// polling JobRepository, not by receiving a reply message through Kafka
```

Bad inline comment:
```java
// set the channel to null
```

The good comment explains the architectural decision (polling vs. reply) and why the code does what it does. The bad comment restates the code.

### 2e. Staleness Check

Read the full file and check if any comments reference:
- Class, method, or field names that no longer exist
- Behavior that was changed in the current diff
- Configuration keys that were renamed or removed
- Outdated package paths (e.g., old Spring Batch 5.x `org.springframework.batch.item.*` packages)
- Deprecated APIs that the code no longer uses

## Step 3: Check README and CLAUDE.md Sync

If any of these changes occurred, flag whether README.md or CLAUDE.md needs updating:

| Change | README section to check |
|--------|------------------------|
| New batch job added | "Sample Batch Jobs" table |
| New profile added | "Profiles" table |
| New module added | Module list and "Project Structure" |
| Build/test commands changed | "Build & Test" section |
| New REST endpoint | "REST Endpoints" section |
| New configuration properties | README config table or CLAUDE.md |
| Architecture changed | Architecture diagram |
| New test category | "Integration Tests" or "E2E Tests" section |

## Step 4: Spring Batch Concept Glossary

When ANY of these concepts appear in a file's code or annotations, the class-level or method-level JavaDoc **must** explain them for readers unfamiliar with Spring Batch. If a concept is used but not explained anywhere in the file, flag it.

| Concept | What must be explained |
|---------|----------------------|
| `@StepScope` | Defers bean creation until step execution time, enabling `@Value("#{stepExecutionContext[...]}")` injection of partition-specific parameters |
| `Partitioner` | Creates a map of `ExecutionContext` objects, one per partition, each carrying partition-specific parameters |
| `ExecutionContext` | Key-value store that travels with each partition — serialized over Kafka in remote mode or passed in-memory in standalone mode |
| `JobRepository` | Database-backed store for job/step metadata; used for distributed locking and manager-side completion polling |
| Chunk processing | Reader -> Processor -> Writer pipeline that processes N items at a time within a single transaction |
| `ItemProcessor` returning `null` | Framework contract for filtering — `null` means "skip this item", increments the step's `filterCount` (not an error) |
| Remote partitioning | Manager step sends partition requests via messaging (Kafka); workers execute steps independently on separate pods |
| `CompositeItemWriter` | Delegates to multiple writers in sequence within the same chunk transaction |
| `KafkaItemReader` | Reads from a Kafka topic with explicit partition assignment (not consumer group protocol) |
| `@Qualifier` on Step beans | Resolves Spring bean ambiguity when multiple `Step` beans exist in the application context |
| `BatchStepNames` constants | Single source of truth for job/step name strings — prevents name drift between configuration and runtime |
| `JobOperator` | Entry point for starting, stopping, and inspecting jobs programmatically (replaces deprecated `JobLauncher` in Spring Batch 6) |
| `BeanFactoryStepLocator` | Resolves Step beans by name from the Spring context for worker-side remote partition execution |
| `RemotePartitioningManagerStepBuilder` | Builder for manager steps that publish partition requests to a messaging channel and poll JobRepository for worker completion |
| `TransactionSynchronization` | Spring mechanism that hooks Kafka producer commit/abort into the database transaction lifecycle for best-effort coordination |
| `TaskExecutorPartitionHandler` | Standalone (non-Kafka) partition handler that runs worker steps in local threads within the same JVM |

## Step 5: Produce the Report

Output a structured report:

```
## Documentation Review Report

### Summary
- Files reviewed: N
- Findings: N (X missing, Y stale, Z unclear, W improvement)
- README/CLAUDE.md update needed: yes/no

### File: path/to/File.java

#### [MISSING] Class-level JavaDoc
No class-level JavaDoc found. This class implements [concept] which readers unfamiliar with
Spring Batch would need explained.
**Suggestion:** Add JavaDoc explaining [specific suggestion with draft text].

#### [STALE] Inline comment at line N
Comment says "X" but the code now does "Y" after the recent change.
**Suggestion:** Update to reflect current behavior.

#### [UNCLEAR] Method JavaDoc for methodName()
The explanation of [concept] assumes familiarity with Spring Batch — a newcomer would not
understand [specific gap].
**Suggestion:** Clarify by explaining [specific improvement].

#### [IMPROVEMENT] Missing @param tag on method()
Parameter `gridSize` is a Spring Batch partitioning hint that deserves explanation.
**Suggestion:** Add `@param gridSize hint for desired partition count — actual count may be
smaller if the file has fewer data lines than gridSize`

### README.md
#### [STALE] "Sample Batch Jobs" table
New job `fooBarJob` was added but is not listed in the README table.
**Suggestion:** Add a row describing the new job's input, output, and strategy.
```

Severity levels:
- **MISSING** — Required documentation is absent (class JavaDoc, public method JavaDoc, constant JavaDoc)
- **STALE** — Documentation contradicts current code behavior
- **UNCLEAR** — Documentation exists but is ambiguous, too terse, or assumes Spring Batch knowledge without explanation
- **IMPROVEMENT** — Documentation works but could be meaningfully better (missing tags, code restatement instead of intent, etc.)

## What NOT to Flag

- Simple getters, setters, `toString()`, `hashCode()`, `equals()` — obvious from signature
- Record component accessors — the record's class-level `@param` tags cover these
- `@Override` methods that merely delegate to super without changing the contract
- `private` helper methods with self-evident names and straightforward logic
- Test method names that clearly describe the scenario (e.g., `shouldLaunchFileRangeJobAndComplete`)
- Log statements — these follow their own conventions documented in CLAUDE.md
- Files with no issues — omit them from the report entirely to keep output concise

## Tone

Suggestions should be specific and constructive — never say "add better documentation." Instead, draft the actual JavaDoc or comment text you'd recommend. Match the project's existing voice: structured, explanatory, uses `{@link}` cross-references, and explains Spring Batch concepts as if the reader is encountering them for the first time.
