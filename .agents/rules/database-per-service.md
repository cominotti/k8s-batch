---
description: Database-per-service topology, Liquibase changelog ownership, and MySQL multi-database conventions
globs: ["helm/**", "**/application.yml", "**/changelog/**", "docker-compose.yml", "**/ContainerHolder.java"]
---

# Database-per-Service Pattern

Each microservice owns its own MySQL logical database within a shared MySQL StatefulSet. This gives strong schema isolation (no cross-domain FKs, independent Liquibase migration timelines) without multiplying infrastructure.

## Database Naming

| Service | Database | Purpose |
|---------|----------|---------|
| `k8s-batch-jobs` | `k8sbatch` | Spring Batch meta-tables + batch job application tables |
| `k8s-batch-crud` | `k8scrud` | Customer/Account domain tables + Hibernate sequence tables |
| Future services | `k8s_<domain>` | e.g., `k8s_orders`, `k8s_inventory` |

## Changelog Ownership

Each service owns its own Liquibase changelogs under `src/main/resources/db/changelog/`. No shared changelog module — each service is fully self-contained in its schema management.

- **Batch service**: `001-003` changelogs (target_records, enriched_transactions, rules_poc)
- **CRUD service**: `004-006` changelogs (customers, accounts, hibernate sequences)
- When adding a new service, create its changelogs in its own module — do NOT add to another service's changelogs

## Helm Chart

- `mysql.auth.database` — primary database created by `MYSQL_DATABASE` env var (batch service)
- `mysql.auth.additionalDatabases` — map of additional databases created by init ConfigMap SQL
- Adding a new service database is a **values-only change** — add an entry to `additionalDatabases`
- `_helpers.tpl` has a generic `k8s-batch.mysql.jdbcUrlFor` helper (accepts `dict "ctx" . "dbName" <name>`), with thin wrappers: `k8s-batch.mysql.jdbcUrl` (batch), `k8s-batch.mysql.crudJdbcUrl` (CRUD). Adding a new service only requires a new 3-line wrapper
- The init ConfigMap `00-create-databases.sql` runs **before** `01-spring-batch-schema.sql` (alphabetical order)

## Docker Compose (Local Dev)

- `MYSQL_DATABASE: k8sbatch` creates the primary database
- `config/mysql-init/00-create-databases.sql` creates additional databases via `docker-entrypoint-initdb.d`
- Each service has its own `SPRING_DATASOURCE_URL` pointing to its database

## Init Script Caveat

`docker-entrypoint-initdb.d` scripts (both local and K8s) **only run on first MySQL initialization** (empty data directory). For existing MySQL instances with a populated PVC:

1. Connect to MySQL as root
2. Run `CREATE DATABASE IF NOT EXISTS k8scrud CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;`
3. Run `GRANT ALL PRIVILEGES ON k8scrud.* TO 'k8sbatch'@'%'; FLUSH PRIVILEGES;`
4. Restart the CRUD service — Liquibase will create tables in the new database

## Oracle Compatibility

`CREATE DATABASE` is MySQL-specific. The init ConfigMap is gated on `database.type != "oracle"`. Oracle uses schemas/tablespaces differently — adapt the pattern accordingly when targeting Oracle.

## Testcontainers

Each test module's `ContainerHolder` creates MySQL with `.withDatabaseName()` matching the service's database:
- `ContainerHolder.java` (batch ITs): `k8sbatch`
- `CrudContainerHolder.java` (CRUD ITs): `k8scrud`
