Review Helm chart changes in `helm/k8s-batch/` against the project's Helm conventions documented in `.claude/CLAUDE.md`.

## Checklist

Check every modified template for:

1. **Init container image**: Must use `{{ .Values.global.initImage }}` — never hardcode `busybox` or any image name
2. **KRaft replication factors**: `KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR`, `KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR`, and `KAFKA_TRANSACTION_STATE_LOG_MIN_ISR` must derive from `min(kafka.replicaCount, 3)`
3. **HPA vs replicas**: App Deployment must omit `replicas` when HPA is enabled
4. **checksum/config annotation**: Deployments that mount ConfigMaps must include `checksum/config` annotation
5. **Schema Registry gating**: Schema Registry resources must be gated on both `schemaRegistry.enabled` AND `features.schemaRegistry`
6. **Image references**: No hardcoded Docker image names or version tags — must come from `values.yaml`
7. **Init container readiness**: Init containers must gate app startup until MySQL, Kafka, and Schema Registry (when enabled) are reachable

## Output

For each issue found, report: file path, line number, what's wrong, and the fix. If no issues found, confirm the changes follow all conventions.
