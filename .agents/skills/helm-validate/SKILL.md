---
name: helm-validate
description: Run the full Helm validation pipeline (lint, unittest, template render) for the k8s-batch chart
disable-model-invocation: true
---

Run the complete Helm validation pipeline for `helm/k8s-batch` in sequence. Stop on the first failure.

1. **Lint**: `helm lint helm/k8s-batch`
2. **Unit tests**: `helm unittest helm/k8s-batch`
3. **Template render**: `helm template test-release helm/k8s-batch` (verify rendering succeeds; do not print full output unless it fails)

Report results concisely: pass/fail per step, and test count from unittest output. If any step fails, show the error and stop.
