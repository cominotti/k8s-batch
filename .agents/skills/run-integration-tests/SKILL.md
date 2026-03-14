---
name: run-integration-tests
description: Build and run integration + E2E tests with Docker and Helm prerequisite checks
disable-model-invocation: true
---

Run the full k8s-batch test suite (integration tests + E2E tests) with prerequisite validation.

**Steps:**

1. **Check prerequisites** (run both checks before proceeding):
   - Verify Docker is running: `docker info > /dev/null 2>&1` — if not available, tell the user and stop
   - Verify Helm CLI is available: `helm version > /dev/null 2>&1` — if not available, tell the user and stop (needed for E2E tests)
2. **Build the Docker image** (required by E2E tests): `docker build -t k8s-batch:e2e .`
3. **Run integration tests**: `TESTCONTAINERS_RYUK_DISABLED=true mvn -pl k8s-batch-integration-tests -am verify`
   - Test output is redirected to files by default (quiet console)
4. **Run E2E tests**: `TESTCONTAINERS_RYUK_DISABLED=true mvn -pl k8s-batch-e2e-tests -am verify`
   - Test output is redirected to files by default (quiet console)
5. **On failure**: if either Maven command exits with non-zero status, read the output files from `target/failsafe-reports/` for the failed test classes. Use `cat <module>/target/failsafe-reports/*-output.txt` to get the captured test output containing error details.

Report test results concisely for each suite: total tests, passed, failed. On failure, include failure details (test name + assertion/error message) from the output files.
