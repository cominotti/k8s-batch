---
name: run-integration-tests
description: Build and run integration tests with Docker prerequisite checks
disable-model-invocation: true
---

Run k8s-batch integration tests with prerequisite validation.

**Steps:**

1. Verify Docker is running: `docker info > /dev/null 2>&1` — if not available, tell the user and stop
2. Run: `TESTCONTAINERS_RYUK_DISABLED=true mvn -pl k8s-batch-integration-tests -am verify`

Report test results concisely: total tests, passed, failed, and any failure details (test name + assertion message).
