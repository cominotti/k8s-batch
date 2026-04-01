# k8s-batch — Roteiro de Apresentação

> Público-alvo: desenvolvedores plenos que não conhecem o código nem necessariamente
> Spring Batch ou testes de integração/E2E com containers.
>
> Duração estimada: 40–50 min (com demos opcionais).

---

## 1. O que é este projeto? (5 min)

**Pitch de elevador:**
Um projeto de referência que demonstra como rodar batch processing escalável
horizontalmente no Kubernetes usando Spring Boot 4 + Spring Batch 6.

O projeto não é um monolito: são **três microsserviços** (batch, CRUD, API gateway)
que compartilham infraestrutura (MySQL, Kafka) mas mantêm isolamento de schema e deploy.

**O que ele resolve na prática:**

- ETL de arquivos CSV para banco de dados, com particionamento automático
- Enriquecimento de eventos financeiros lidos de Kafka (Avro + Schema Registry)
- Um PoC de motor de regras (Drools, DMN, EVRete) para scoring de risco
- Um CRUD de Customer/Account com JPA/Hibernate 7, servindo de exemplo de microsserviço
  isolado com banco próprio
- Um API Gateway que roteia para os dois backends com rate limiting e circuit breaker

---

## 2. Stack tecnológica (3 min)

| Camada          | Tecnologia                                                     |
|-----------------|----------------------------------------------------------------|
| Linguagem       | Java 21                                                        |
| Framework       | Spring Boot 4.0.3, Spring Batch 6.0.2, Spring Cloud 2025.1.1  |
| Persistência    | MySQL 8.4 (JPA/Hibernate 7.x), Liquibase (migrations)         |
| Mensageria      | Kafka (Confluent 7.9.0, KRaft — sem Zookeeper)                |
| Serialização    | Avro 1.12 + Confluent Schema Registry                         |
| Regras          | Drools 10.x (DRL + Rule Units), KIE DMN, EVRete               |
| Deploy          | Helm 3, Kubernetes (K3s nos testes E2E)                        |
| Testes          | JUnit 5, Testcontainers 2.0.3, Fabric8 K8s Client, AssertJ    |
| Observabilidade | Actuator, Prometheus (métricas), logback structured logging    |

**Destaque para a audiência:**
Se você nunca usou Spring Batch, pense nele como um framework que organiza
processamento em lote em três fases: *ler → processar → escrever*, em blocos
de N registros (chunks). Ele cuida de transações, retry, restart e metadados de
execução automaticamente.

---

## 3. Estrutura de módulos (5 min)

O projeto é um Maven multi-module. Cada módulo tem uma responsabilidade clara:

```
k8s-batch/                          (POM pai)
│
├── k8s-batch-rules-kie/            Biblioteca: tipos de domínio e adaptadores KIE
│                                   (DMN, Drools Rule Units). JAR simples, sem Spring Boot.
│
├── k8s-batch-jobs/                 App principal: REST API + 4 batch jobs + configs.
│                                   Depende de rules-kie. Gera fat JAR (-exec).
│
├── k8s-batch-crud/                 Microsserviço CRUD: Customer/Account com JPA.
│                                   App Spring Boot independente. Gera fat JAR (-exec).
│
├── k8s-batch-api-gateway/          API Gateway: Spring Cloud Gateway (Servlet, não reativo).
│                                   Rate limiting (Bucket4j) + circuit breaker (Resilience4j).
│
├── k8s-batch-integration-tests/    Testes de integração do batch (Testcontainers).
├── k8s-batch-crud-tests/           Testes de integração do CRUD (Testcontainers).
├── k8s-batch-api-gateway-tests/    Testes de integração do gateway (WireMock).
└── k8s-batch-e2e-tests/            Testes E2E: deploy real no K3s via Testcontainers.
```

**Por que separar módulos de teste?**
Os módulos `*-tests` existem separados para manter dependências de teste (Testcontainers,
Fabric8, WireMock) fora do artefato de produção. O JAR de produção fica limpo.

**Detalhe do classifier `-exec`:**
O Spring Boot repackage gera dois JARs: o thin (para outros módulos dependerem como
biblioteca) e o fat `-exec` (para rodar como aplicação). Isso permite que os módulos
de teste importem classes do módulo principal sem puxar todo o Spring Boot empacotado.

---

## 4. Os três microsserviços e como interagem (7 min)

### 4.1 Visão geral da arquitetura

```
                          +-------------------+
                          |   API Gateway     |  porta 9090
                          | (rate limit, CB)  |
                          +---------+---------+
                                    │
                     +--------------+--------------+
                     │                             │
              +------+------+              +-------+------+
              |  Batch App  |              |  CRUD App    |  porta 8081
              | porta 8080  |              |  Customer /  |
              | (REST + Jobs)|             |  Account     |
              +------+------+              +-------+------+
                     │                             │
        +------------+------------+                │
        │            │            │                │
   +----+----+ +----+----+ +-----+-----+    +-----+-----+
   |  MySQL  | |  Kafka  | |  Schema   |    |   MySQL   |
   | k8sbatch| | (KRaft) | |  Registry |    |  k8scrud  |
   +---------+ +---------+ +-----------+    +-----------+
                                            (mesmo StatefulSet,
                                             database diferente)
```

### 4.2 Batch App (`k8s-batch-jobs`)

- **Entrypoint:** `K8sBatchApplication`
- **REST API:** `POST /api/jobs/{jobName}` lança jobs assincronamente (HTTP 202).
  `GET /api/jobs/{jobName}/executions/{id}` consulta status.
- **4 batch jobs** configurados (detalhados na seção 5)
- **Profiles** controlam o modo de particionamento:
  - `remote-partitioning,remote-kafka` — workers em pods separados via Kafka
  - `remote-partitioning,remote-jms` — via RabbitMQ ou SQS
  - `standalone` — threads locais, sem broker

### 4.3 CRUD App (`k8s-batch-crud`)

- **Entrypoint:** `CrudApplication`
- **REST API:**
  - `POST/GET/PUT/DELETE /api/customers` — ciclo de vida completo
  - `POST/GET/PUT/DELETE /api/accounts` — vinculados a um Customer
  - `GET /api/customers/{id}/accounts` — eager fetch com `@EntityGraph` (evita N+1)
- **Arquitetura hexagonal:** `adapters/<port>/<tech>/` para REST, `domain/` para services
- **JPA/Hibernate 7.x:** `@NaturalId` para business keys, `@Version` para optimistic locking,
  `@ManyToOne(LAZY)` sempre, `Set` para `@OneToMany`
- Erro handling com `ProblemDetail` (RFC 9457): 404 para not found, 409 para conflito
  de versão (optimistic lock)

### 4.4 API Gateway (`k8s-batch-api-gateway`)

- **Entrypoint:** `GatewayApplication`
- Roteia para batch (`/api/jobs/**`) e CRUD (`/api/customers/**`, `/api/accounts/**`)
- Bucket4j rate limiting + Resilience4j circuit breaker
- Spring Cloud Gateway Server MVC (Servlet-based, roda em virtual threads)
- Disponível tanto no Docker Compose (porta 9090) quanto no Helm chart

---

## 5. Os batch jobs em detalhe (7 min)

### Conceito-chave: chunk processing

Antes de falar dos jobs, vale entender o modelo mental do Spring Batch:

```
+----------+     +----------+     +----------+
|  Reader  | --> |Processor | --> |  Writer  |   × N registros por "chunk"
+----------+     +----------+     +----------+
                                       │
                                  [commit TX]
```

O Reader lê um item por vez, o Processor transforma, e quando acumula N itens (chunk size),
o Writer grava tudo em batch e faz commit. Se falhar, só o chunk atual faz rollback.

### Conceito-chave: particionamento

Para escalar, o Spring Batch divide o trabalho em partições. Um **Manager Step** cria N
partições (ex: "linhas 1–1000", "linhas 1001–2000") e envia cada uma para um **Worker Step**.
No modo *standalone*, os workers rodam em threads locais. No modo *remote*, os workers
rodam em pods separados, comunicando-se via Kafka (ou JMS).

```
              Manager Step
             /    │    \
            /     │     \
     Worker 1  Worker 2  Worker 3   ← pods separados (remote) ou threads (standalone)
     CSV[1-1k] CSV[1k-2k] CSV[2k-3k]
```

### 5.1 fileRangeEtlJob — ETL por faixa de linhas

- Divide UM arquivo CSV em faixas de linhas (`FileRangePartitioner`)
- Cada worker lê sua faixa, filtra registros inválidos, faz upsert no MySQL
- Upsert via `ON DUPLICATE KEY UPDATE` (idempotente para re-execuções)

### 5.2 multiFileEtlJob — ETL multi-arquivo

- Divide um DIRETÓRIO de CSVs: cada arquivo vira uma partição (`MultiFilePartitioner`)
- Mesmo fluxo: reader → filtro → upsert

### 5.3 transactionEnrichmentJob — Kafka para banco + Kafka

- **Não usa particionamento Spring Batch** — a paralelização vem das partições Kafka
- Lê eventos Avro `TransactionEvent` de um tópico Kafka
- Enriquece com taxa de câmbio e score de risco
- Escreve em DOIS destinos (`CompositeItemWriter`): MySQL + tópico Kafka de saída
- Requer Schema Registry (Avro)
- Só ativo com profile `remote-kafka`

### 5.4 rulesEnginePocJob — PoC de motores de regras

- Lê transações financeiras de CSV
- Aplica regras de negócio via um dos quatro motores (selecionado por property):
  - `drools` — Drools DRL clássico (arquivo `.drl`)
  - `evrete` — EVRete (regras em Java puro)
  - `dmn` — KIE DMN (tabelas de decisão em XML)
  - `drools-ruleunit` — Drools Rule Units (OOPath)
- Escreve resultado enriquecido no MySQL
- Troca de motor via `batch.rules.engine=drools|evrete|dmn|drools-ruleunit`

---

## 6. Kafka no projeto (3 min)

### Tópicos

| Tópico                         | Partições | Uso                                          |
|--------------------------------|-----------|----------------------------------------------|
| `batch-partition-requests`     | 10        | Manager envia trabalho para workers           |
| `batch-partition-replies`      | 10        | Workers reportam conclusão ao manager         |
| `transaction-events`           | 10        | Entrada do job de enriquecimento (Avro)       |
| `enriched-transaction-events`  | 10        | Saída do job de enriquecimento (Avro)         |

### KRaft (sem Zookeeper)

O Kafka roda em KRaft mode — o controller de metadados é integrado ao próprio broker.
Simplifica o deploy no Kubernetes (um StatefulSet em vez de dois).

### Schema Registry

Registra e valida schemas Avro. Garante compatibilidade entre producer e consumer
quando o schema evolui. Deployado como Deployment (stateless — armazena schemas
em um tópico Kafka interno `_schemas`).

---

## 7. Database-per-service (3 min)

```
+-------------------------------+
|        MySQL StatefulSet      |
|  +----------+ +-----------+  |
|  | k8sbatch | |  k8scrud  |  |
|  | (batch)  | |  (CRUD)   |  |
|  +----------+ +-----------+  |
+-------------------------------+
```

**Um MySQL, dois databases lógicos.** Cada serviço tem:

- Seu próprio database (`k8sbatch` vs `k8scrud`)
- Seus próprios changelogs Liquibase (batch: 001–003, CRUD: 004–006)
- Nenhuma FK entre databases — isolamento total de schema

**Por que não dois MySQL separados?**
Para um projeto de referência, um StatefulSet com databases lógicos dá o isolamento
necessário sem multiplicar infraestrutura. Em produção com times diferentes, você
separaria em instâncias distintas.

**Criação dos databases:**
- Em K8s: `mysql.auth.additionalDatabases` no `values.yaml` gera um init ConfigMap SQL
- Em Docker Compose: `config/mysql-init/00-create-databases.sql`
- **Cuidado:** init scripts só rodam na primeira inicialização (data dir vazio).
  Clusters existentes precisam de `CREATE DATABASE` manual.

---

## 8. Helm chart e deploy no Kubernetes (5 min)

### Estrutura do chart

```
helm/k8s-batch/
├── templates/
│   ├── app/        Deployment, Service, HPA, ConfigMap, Secret, Ingress (batch)
│   ├── crud/       Deployment, Service, HPA, ConfigMap (CRUD)
│   ├── gateway/    Deployment, Service, HPA, ConfigMap (API Gateway)
│   ├── kafka/      StatefulSet, Services, Schema Registry, Job de criação de tópicos
│   ├── mysql/      StatefulSet, Service, Secret, ConfigMap (init scripts)
│   └── _helpers.tpl
├── values.yaml     (~550 linhas, fortemente comentado)
└── tests/          85 testes unitários Helm (helm-unittest)
```

### Nuances importantes

1. **Init containers em cadeia:** o pod da app só starta após MySQL, Kafka e
   Schema Registry estarem prontos (init containers com `nc -z` em loop)

2. **HPA vs replicas:** quando HPA está ativo, o Deployment omite `replicas`
   para evitar conflito (Helm tentaria resetar o que o HPA escalou)

3. **MySQL probes usam `tcpSocket`, não `mysqladmin`:** o `mysqladmin ping`
   falha por causa de paths de Unix socket divergentes na imagem Docker

4. **`startupProbe` no MySQL é obrigatório:** a primeira inicialização pode levar
   5–10 min no CI. Sem startup probe, o liveness mata o container antes de terminar

5. **Tópicos Kafka criados por Job:** um Helm hook `post-install/post-upgrade`
   itera o mapa `kafka.topics` do values.yaml. Adicionar tópico é só mudar values

6. **`checksum/config` no Deployment:** qualquer mudança no ConfigMap dispara
   rolling restart automático

---

## 9. Infraestrutura de testes (7 min)

### 9.1 Pirâmide de testes do projeto

```
          /\
         /  \      E2E (21 testes)
        / E2E\     K3s real + Helm deploy + port-forward
       /------\
      /        \   Integração (78 batch + 13 CRUD + 8 gateway)
     /   ITs    \  Testcontainers (MySQL, Redpanda, RabbitMQ, Oracle)
    /------------\
   /              \ Unitários (61 rules-kie)
  /   Unitários    \ JUnit puro, sem containers
 /------------------\
```

### 9.2 Testes de integração (`*IT.java`)

**O que são:** testes que sobem containers Docker reais via Testcontainers e executam
o Spring Context contra eles.

**Containers gerenciados por `ContainerHolder` (singleton):**
- MySQL 8.4 (sempre)
- Redpanda (substituto leve do Kafka — startup de 5–10s vs 30–60s do Confluent)
- RabbitMQ (apenas para testes JMS)
- Oracle (lazy, apenas para `OracleSchemaIT`)

**Três contextos Spring distintos** (evita restart desnecessário entre testes):

| Contexto | Profiles                              | Containers       | Uso                        |
|----------|---------------------------------------|------------------|----------------------------|
| A        | `integration-test,remote-kafka` + web | MySQL + Redpanda | Remote partitioning + REST |
| B        | `integration-test,remote-kafka` (sem web) | MySQL + Redpanda | Remote batch-only      |
| C        | `integration-test,standalone`         | Apenas MySQL     | Standalone + rules engine  |

**Startup paralelo:** `Startables.deepStart(MySQL, Redpanda).join()` sobe os containers
em paralelo em vez de sequencialmente.

**Redpanda como substituto do Kafka nos ITs:**
Redpanda é API-compatível com Kafka e inclui Schema Registry embutido. Sobe em 5–10s
(vs 30–60s do Confluent Kafka). Os E2E usam Confluent real via Helm.

### 9.3 Testes E2E (`*E2E.java`)

**O que são:** testes que deployam o Helm chart inteiro dentro de um cluster K3s
rodando em um container Docker, e verificam o sistema de ponta a ponta.

**Fluxo de um teste E2E:**

```
1. Testcontainers sobe um container K3s
2. Docker images são carregadas no K3s (docker save → ctr import)
3. Helm chart é renderizado (helm template) e aplicado via Fabric8 client
4. DeploymentWaiter espera todos os pods ficarem Ready (timeout 5 min)
5. Port-forwards são abertos (app:8080, MySQL:3306, gateway:9090, CRUD:8081)
6. Testes chamam REST endpoints e verificam dados no MySQL via JDBC
7. Cluster K3s é REUSADO entre classes de teste do mesmo profile
```

**8 classes de teste E2E:**

| Classe                        | Profile    | O que testa                                 |
|-------------------------------|------------|---------------------------------------------|
| `DeployHealthCheckE2E`        | remote     | Health do app, MySQL, Kafka, Schema Registry |
| `FileRangeJobE2E`             | remote     | ETL CSV por faixa de linhas via Kafka        |
| `MultiFileJobE2E`             | remote     | ETL multi-arquivo via Kafka                  |
| `PartitionDistributionE2E`    | remote     | Distribuição de partições entre pods         |
| `TransactionEnrichmentJobE2E` | remote     | Enriquecimento Kafka→MySQL→Kafka             |
| `GatewayRoutingE2E`           | remote     | Roteamento do gateway para batch e CRUD      |
| `CustomerCrudE2E`             | standalone | CRUD lifecycle completo no K8s               |
| `StandaloneProfileE2E`        | standalone | ETL standalone (sem Kafka)                   |

**Nuance: reuso do cluster K3s.**
O K3s não é destruído entre classes. Quando o profile muda (ex: de remote para standalone),
o `K3sClusterManager` faz teardown do deploy anterior e re-aplica com o novo values file.
Isso economiza ~3 min de startup do K3s por suite.

**Nuance: `@TestInstance(PER_CLASS)`.**
O setup (Helm deploy, port-forward) roda uma vez por classe, não por método.
Cleanup de dados roda no `@BeforeEach` (não `@AfterEach`) para que testes que falham
deixem dados para análise post-mortem.

**Nuance: database-per-service nos E2E.**
O `MysqlVerifier` do batch conecta no database `k8sbatch`. O CRUD tem seu próprio
`crudMysqlVerifier` conectando em `k8scrud`. Cada serviço precisa de um verificador
separado — não dá para consultar tabelas de um database na conexão do outro.

### 9.4 Como rodar

```bash
# Unitários (rápido, sem Docker)
mvn test

# Integração do batch (precisa de Docker rodando)
TESTCONTAINERS_RYUK_DISABLED=true mvn -pl k8s-batch-integration-tests -am verify

# Integração do CRUD
TESTCONTAINERS_RYUK_DISABLED=true mvn -pl k8s-batch-crud-tests -am verify

# E2E (precisa de Docker + Helm CLI)
docker build -t k8s-batch:e2e .
docker build -f Dockerfile.crud -t k8s-batch-crud:e2e .
docker build -f Dockerfile.gateway -t k8s-batch-api-gateway:e2e .
TESTCONTAINERS_RYUK_DISABLED=true mvn -pl k8s-batch-e2e-tests -am verify

# Tudo (integração + E2E)
TESTCONTAINERS_RYUK_DISABLED=true mvn verify

# Helm unit tests (sem Docker, ~140ms)
helm unittest helm/k8s-batch
```

**`TESTCONTAINERS_RYUK_DISABLED=true`** é necessário neste ambiente. O Ryuk é um
container auxiliar que o Testcontainers usa para limpeza, mas ele causa problemas
com certos runtimes de container.

---

## 10. Desenvolvimento local com Docker Compose (2 min)

```bash
docker-compose up -d    # sobe MySQL, Kafka, Schema Registry, app batch, CRUD, gateway
```

**Serviços disponíveis:**

| Serviço          | Porta | URL                                   |
|------------------|-------|---------------------------------------|
| Batch App        | 8080  | `http://localhost:8080/api/jobs`      |
| CRUD App         | 8081  | `http://localhost:8081/api/customers` |
| API Gateway      | 9090  | `http://localhost:9090/api/jobs`      |
| MySQL            | 3306  | `mysql -h localhost -u k8sbatch`      |
| Kafka            | 9092  | Bootstrap servers                     |
| Schema Registry  | 8082  | `http://localhost:8082/subjects`      |

O gateway roteia para batch e CRUD — qualquer endpoint acessível diretamente
nas portas 8080/8081 também funciona via 9090, com rate limiting e circuit breaker.

---

## 11. Resumo visual (slide de fechamento)

```
+------------------------------------------------------------------+
|                        k8s-batch                                  |
|                                                                   |
|  3 microsserviços:  Batch (4 jobs) | CRUD (JPA) | Gateway        |
|  Infra:             MySQL (2 DBs)  | Kafka (KRaft) | Schema Reg  |
|  Deploy:            Helm 3 no K8s  | Docker Compose local        |
|  Testes:            61 unit + 99 IT + 21 E2E = 181 testes        |
|  CI:                SPDX + Checkstyle + Helm lint/unittest        |
|                     + kubeconform + K3s smoke test                |
+------------------------------------------------------------------+
```

---

## Apêndice: glossário rápido

| Termo                    | O que é                                                         |
|--------------------------|-----------------------------------------------------------------|
| **Chunk**                | Bloco de N registros processados em uma única transação         |
| **Partitioner**          | Divide o trabalho em fatias para processamento paralelo         |
| **Manager Step**         | Orquestra a distribuição de partições para workers              |
| **Worker Step**          | Executa o processamento de uma partição específica              |
| **Remote Partitioning**  | Workers rodam em pods separados, coordenados via Kafka/JMS      |
| **KRaft**                | Modo do Kafka sem Zookeeper (controller integrado ao broker)    |
| **Avro**                 | Formato de serialização binária com schema evolutivo            |
| **Schema Registry**      | Serviço que registra e valida schemas Avro                      |
| **Testcontainers**       | Biblioteca Java que sobe containers Docker dentro dos testes    |
| **K3s**                  | Distribuição leve de Kubernetes (usada nos testes E2E)          |
| **Liquibase**            | Ferramenta de migração de schema de banco (como Flyway)         |
| **HPA**                  | Horizontal Pod Autoscaler — escala pods automaticamente no K8s  |
| **Circuit Breaker**      | Padrão que interrompe chamadas a serviços degradados            |
| **Optimistic Locking**   | Controle de concorrência via campo `@Version` na entidade       |
| **`@StepScope`**         | Bean criado por partição — cada worker recebe sua instância     |
