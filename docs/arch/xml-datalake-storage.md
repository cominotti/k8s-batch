# XML Datalake Storage: Evaluation of Retrieval-Oriented Solutions

## Context

A system requires a "datalake" composed of many thousands of XML documents that must be queried for retrieval. This document evaluates whether Apache Parquet is a good fit, analyzes its alignment with k8s-batch's architecture, and compares three Java-based alternatives with tradeoffs and a recommendation.

### Clarifying "Datalake"

The term "datalake" carries two very different interpretations:

1. **Document repository** — store XML documents, retrieve them by content or metadata queries.
2. **Analytical datalake** — flatten XML into columnar format for aggregate analytics (Spark/Presto).

The "queried for retrieval" requirement points to scenario 1. This evaluation focuses on document storage and retrieval, not columnar analytics.

---

## Evaluation Framework

Each solution is evaluated against these criteria:

| Criterion | Description |
|-----------|-------------|
| **XML fidelity** | Preserves full XML structure, namespaces, mixed content, document order |
| **Query capability** | Supported XML query languages (XQuery, XPath, Lucene, SQL) |
| **Retrieval performance** | Speed for point lookups and filtered retrieval |
| **Java integration** | Library maturity, Maven artifacts, Spring compatibility |
| **K8s deployment** | Containerization, statefulness, operators |
| **Batch processing fit** | Works with Spring Batch chunk-oriented patterns |
| **Operational complexity** | New infrastructure burden relative to existing stack |

---

## 1. Apache Parquet — Poor Fit

### Why Parquet Fails for XML

Parquet is a columnar storage format optimized for analytical scans over tabular data. XML documents are hierarchical, variably-structured, and ordered — three properties that conflict fundamentally with Parquet's design.

| XML property | Parquet reality |
|---|---|
| Deeply nested, variable-depth trees | Dremel encoding handles *known* repeated/optional fields, not arbitrary-depth trees |
| Mixed content (text + child elements in same node) | No concept of mixed content |
| Document order matters | Columns have no inherent order relationship |
| Namespaces, attributes vs. elements | No equivalent — everything is a flat field |
| Variable schemas across documents | Requires a unified schema upfront |

### Forcing XML Into Parquet — Three Bad Options

- **Store raw XML as a `STRING` column** — defeats the purpose entirely. No predicate pushdown, no column projection on XML content. Parquet becomes a worse filesystem.
- **Shred XML into columns** — massive schema complexity, loss of document structure, expensive reconstruction. Only works if all documents share a rigid, known schema.
- **Hybrid (metadata columns + XML blob)** — partial benefit for metadata queries, but retrieval still requires full deserialization.

### Fit With k8s-batch

k8s-batch already uses Avro (a row-based format with Schema Registry) for Kafka event streaming. Parquet would be a second serialization format with no clear benefit. The project's batch patterns — chunk-oriented read/transform/write — work with document-oriented inputs (CSV, Kafka), not columnar scans. Parquet adds infrastructure (Spark/Presto for querying) that does not align with the existing Spring Batch processing model.

### Why Parquet Dominates "Datalake" Conversations But Fails Here

Parquet's success comes from the convergence of columnar storage + predicate pushdown + compression for *tabular analytics*. When people say "datalake," they usually mean Parquet/ORC files on S3 queried by Spark/Presto. But that pattern assumes data can be flattened into rows and columns. XML documents resist flattening because their value *is* the hierarchy. The right mental model for an XML datalake is closer to a *document archive* than a *data warehouse* — and the tooling follows accordingly.

**Verdict: Parquet is the wrong tool.** It excels at analytical queries over structured tabular data, but XML document retrieval is not that.

---

## 2. BaseX — Native XML Database

[BaseX](https://basex.org/) is an open-source, lightweight XML database written entirely in Java. It is purpose-built for storing and querying XML documents.

### Capabilities

- Full XQuery 3.1 / XPath 3.1 / XSLT 3.0
- Integrated full-text search
- ACID transactions
- REST API + Java client API (`org.basex:basex` on Maven Central)
- Handles millions of documents; many thousands is trivial
- BSD license

### Java Integration

```java
// Direct Java API (in-process or client/server)
try (ClientSession session = new ClientSession("basex-host", 1984, "admin", "pw")) {
    session.execute("OPEN xml-datalake");
    String result = session.execute("XQUERY //invoice[@amount > 10000]");
}
```

Spring Batch `ItemReader` would query BaseX via REST or the Java client, paginating through results in chunk-sized batches.

### Tradeoffs

| Pro | Con |
|---|---|
| Perfect XML fidelity — preserves namespaces, order, mixed content | Another stateful service on K8s (needs PV for data) |
| XQuery is the most powerful XML query language available | Smaller community than Solr/Elasticsearch |
| Written in Java — same runtime, easy debugging | No Spring Data module (use raw client or REST) |
| Lightweight single-process (JVM), official Docker images | Scaling beyond single-node requires application-level sharding |
| BSD license | No built-in clustering (unlike SolrCloud) |

### k8s-batch Fit: Medium-High

Adds one StatefulSet (similar to how MySQL is deployed in the Helm chart). The Java client integrates naturally with Spring Batch. The query model (XQuery) is the right tool for XML. But it is a niche technology — fewer operators and K8s patterns available compared to more mainstream databases.

**Best for:** Complex XQuery with joins across documents, aggregations, or full-text search within XML structure — especially when queries need to be fast and interactive (not batch).

---

## 3. Apache Solr — Search-Oriented Retrieval

[Apache Solr](https://solr.apache.org/) is a search platform built on Lucene. It does not store XML natively, but it is the strongest option if retrieval means "find documents matching complex criteria fast."

### Approach

Index metadata and extracted fields from XML. Store the original XML as a stored field for retrieval. Query via Lucene syntax or JSON API.

### Java Integration

```java
// SolrJ client — mature, well-documented
SolrClient solr = new Http2SolrClient.Builder("http://solr:8983/solr/xml-docs").build();

// Indexing (in a Spring Batch ItemWriter)
SolrInputDocument doc = new SolrInputDocument();
doc.addField("id", xmlDocId);
doc.addField("raw_xml", rawXmlString);           // stored, not indexed
doc.addField("invoice_amount", extractedAmount);  // indexed + stored
doc.addField("customer_name", extractedName);     // indexed + stored
solr.add(doc);

// Retrieval
QueryResponse resp = solr.query(
    new SolrQuery("customer_name:Acme AND invoice_amount:[10000 TO *]"));
```

### Tradeoffs

| Pro | Con |
|---|---|
| Outstanding retrieval speed — purpose-built for search | Not an XML-native store; XPath/XQuery not supported |
| Faceted search, fuzzy matching, relevance ranking | Requires XML-to-field extraction at ingest time (schema design needed) |
| SolrJ client is rock-solid | SolrCloud needs ZooKeeper (additional infra) |
| Solr Operator for K8s exists | Heavier footprint than BaseX |
| Apache 2.0 license, massive community | Changing what is queryable requires re-indexing |
| Scales horizontally (sharding + replicas) | Overkill if queries are simple XPath lookups |

### k8s-batch Fit: Medium

Solr is a good fit for *retrieval patterns* (find documents by criteria), and the SolrJ-to-Spring-Batch integration is straightforward. But it adds significant infrastructure (Solr + ZooKeeper) and requires upfront schema design for which XML fields to index.

**Best for:** End-user-facing search (typeahead, fuzzy matching, relevance ranking, faceted navigation). Solr is unmatched for search UX. But it is a search engine, not an XML database.

---

## 4. Saxon-HE + Object Storage (MinIO) + MySQL Metadata Index

This is the custom-but-lean approach: store XML files in object storage, index metadata in the existing MySQL, and use Saxon for XQuery/XPath processing at the application layer.

### Architecture

```
┌─────────────────────────────────────────────────────┐
│                   Spring Batch Job                   │
│  ItemReader (query MySQL index -> fetch from MinIO)  │
│  ItemProcessor (Saxon XQuery/XPath for filtering)    │
│  ItemWriter (MySQL / Kafka / wherever)               │
└──────────┬──────────────────┬───────────────────────┘
           │                  │
    ┌──────▼──────┐   ┌──────▼──────┐
    │   MySQL     │   │    MinIO    │
    │  (metadata  │   │  (raw XML   │
    │   index)    │   │   files)    │
    └─────────────┘   └─────────────┘
```

### Java Integration

```java
// Saxon-HE for XQuery/XPath processing
Processor processor = new Processor(false); // Saxon-HE
XQueryCompiler compiler = processor.newXQueryCompiler();
XQueryExecutable query = compiler.compile("//transaction[@amount > 10000]");
XQueryEvaluator eval = query.load();
eval.setSource(new StreamSource(xmlInputStream));
XdmValue result = eval.evaluate();

// MinIO Java SDK for object storage
MinioClient minio = MinioClient.builder()
    .endpoint("http://minio:9000")
    .credentials("access", "secret")
    .build();
InputStream xmlStream = minio.getObject(
    GetObjectArgs.builder().bucket("xml-datalake").object(docKey).build());
```

### Tradeoffs

| Pro | Con |
|---|---|
| No new database — reuses existing MySQL for metadata | Requires custom code (reader, indexing, query layer) |
| MinIO is K8s-native, lightweight, trivial to deploy | Full-collection XQuery scans are application-side, not DB-optimized |
| Saxon-HE is the gold standard for XQuery/XSLT in Java | Saxon-HE has some limitations vs. Saxon-EE (no streaming, no schema-aware) |
| Object storage scales to millions of files effortlessly | Two systems to keep consistent (MySQL index + MinIO files) |
| Fits batch patterns perfectly (read files, process chunks) | Not real-time — batch-oriented by design |
| Mozilla Public License 2.0 (Saxon-HE), Apache 2.0 (MinIO) | More moving parts in application code |

### Saxon's Position in the Java XML Ecosystem

Saxon is maintained by Michael Kay, the editor of the XSLT 2.0 and 3.0 W3C specifications. Saxon-HE (Home Edition) is open-source and covers XQuery 3.1, XPath 3.1, and XSLT 3.0. Saxon-EE adds schema-aware processing and streaming — relevant if individual XML documents are very large (100MB+). For a batch system processing many small-to-medium documents, HE is sufficient.

### MinIO vs. S3 for K8s

MinIO's operator for Kubernetes is production-grade and widely used. It provides an S3-compatible API, meaning code works identically against AWS S3 or on-prem MinIO. This is the same portability pattern k8s-batch already follows with Kafka (Confluent in Helm chart, Redpanda in tests).

### k8s-batch Fit: Highest

This approach mirrors existing patterns in the project:

- Object storage replaces the filesystem (CSV files become MinIO objects)
- MySQL metadata index is analogous to how the project already uses MySQL for application data
- Saxon processing in `ItemProcessor` mirrors the existing `CsvRecordProcessor` / `RulesEngineProcessor` patterns
- MinIO deploys as a StatefulSet in the Helm chart, similar to the existing MySQL setup
- The project already processes DMN files (which are XML) via KIE — Saxon would be a natural complement

---

## Comparison Matrix

| Criterion | Parquet | BaseX | Solr | Saxon + MinIO |
|---|---|---|---|---|
| **XML fidelity** | Poor (loses structure) | Perfect | Partial (stores blob) | Perfect |
| **Query language** | None for XML | XQuery 3.1 / XPath 3.1 | Lucene syntax | XQuery 3.1 / XPath 3.1 |
| **Retrieval speed** | N/A | Excellent | Outstanding | Good (app-side) |
| **Java integration** | parquet-mr | basex (Java native) | SolrJ | Saxon-HE + MinIO SDK |
| **K8s deployment** | Needs Spark/Presto | StatefulSet | StatefulSet + ZK | StatefulSet (MinIO) |
| **Batch fit** | Poor | High | Medium | Highest |
| **New infra** | Spark cluster | BaseX server | Solr + ZooKeeper | MinIO only |
| **License** | Apache 2.0 | BSD | Apache 2.0 | MPL 2.0 + Apache 2.0 |
| **Scale ceiling** | Billions (wrong tool) | Millions | Billions | Millions |

---

## Recommendation: Saxon-HE + MinIO + MySQL Metadata Index

For k8s-batch, the Saxon + MinIO approach is the strongest fit for five reasons:

### 1. Architectural Alignment

k8s-batch is a batch processing system with chunk-oriented jobs. The Saxon + MinIO approach maps directly to existing patterns: an `ItemReader` that queries the MySQL index and fetches from MinIO, an `ItemProcessor` that applies Saxon XQuery/XPath, and an `ItemWriter` that outputs results. This is exactly how the CSV ETL jobs and the rules engine PoC already work — just with XML instead of CSV.

### 2. Minimal Infrastructure Delta

The project already runs MySQL on K8s. MinIO is a single StatefulSet addition. No new database category (no BaseX to learn/operate), no heavy search infrastructure (no Solr + ZooKeeper). Saxon-HE is a Maven dependency, not a service.

### 3. Right Query Model

Saxon-HE supports XQuery 3.1 and XPath 3.1 — the native query languages for XML. Unlike Solr (which requires pre-extraction of indexed fields) or Parquet (which cannot query XML at all), Saxon works with the XML as-is.

### 4. Scaling Path

For many thousands of documents, the MySQL metadata index handles point lookups and filtered retrievals efficiently. If scale grows to millions, MinIO supports S3-compatible lifecycle policies, and partitioning strategies (by date, source, document type) can be applied to the object key scheme. Spring Batch's existing remote partitioning via Kafka would parallelize processing across pods naturally.

### 5. Escape Hatch

If requirements later shift toward real-time full-text search or complex faceted navigation (not batch retrieval), Solr can be layered on top without changing the storage layer — Solr indexes MinIO content, MySQL metadata, or both.

### When to Choose BaseX Instead

If the query patterns are complex XQuery with joins across documents, aggregations, or full-text search within XML structure — and these queries need to be fast and interactive (not batch). BaseX's query optimizer will outperform application-side Saxon for complex multi-document queries.

### When to Choose Solr Instead

If "retrieval" means end-user-facing search (typeahead, fuzzy matching, relevance ranking, faceted navigation). Solr is unmatched for search UX. But it is a search engine, not an XML database.
