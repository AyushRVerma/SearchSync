# Near-Real-Time Product Search

A portfolio-ready CDC pipeline that treats PostgreSQL as the system of record and maintains an Elasticsearch search projection without dual-writing from the request path.

```text
Product REST API -> PostgreSQL WAL -> Debezium/Kafka Connect -> Kafka
                                                              |
Search REST API <- Elasticsearch <- Spring Kafka consumer <---+
```

The Kafka-to-Elasticsearch leg is **at least once**. Elasticsearch upserts and deletes use the PostgreSQL product ID, so repeated delivery is safe. Kafka and Elasticsearch do not participate in one atomic transaction.

> 💡 **Looking for a simplified walkthrough?** See [PROJECT_FLOW.md](PROJECT_FLOW.md) for a plain-English, step-by-step breakdown and diagrams of how data travels through this pipeline.

## Stack

- Java 21 and Spring Boot 3.5.16
- PostgreSQL 17.10 with logical replication
- Apache Kafka 4.1.1 in KRaft mode
- Debezium/Kafka Connect 3.4.3.Final
- Elasticsearch 9.5.2 and Java client 9.5.0
- Flyway, Spring Kafka, Actuator, OpenAPI, JUnit 5, and Testcontainers dependencies

All runtime images and direct dependency versions are pinned. Elasticsearch authentication and Kafka encryption are disabled for local development only.

## Prerequisites

- Java 21–25
- Docker Desktop or another Docker-compatible runtime
- PowerShell on Windows

The included Maven Wrapper is the preferred Maven entry point after it has been generated in the repository.

## Start locally

Copy `.env.example` to `.env` if you want to change the Compose and connector defaults. Never commit real credentials. If you change the application database credentials, also export matching `DB_USER` and `DB_PASSWORD` values before starting Spring Boot.

```powershell
.\scripts\start-local.ps1
.\mvnw.cmd spring-boot:run
```

The first command starts PostgreSQL, Kafka, Kafka Connect, and Elasticsearch, waits for health checks, provisions the product and dead-letter topics, and registers the Debezium connector. The second starts the API. Run it in a separate terminal.

If running the steps manually:

```powershell
docker compose up -d --wait
.\scripts\register-connector.ps1
.\mvnw.cmd spring-boot:run
```

Useful endpoints:

- API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui.html`
- Health: `http://localhost:8080/actuator/health`
- Kafka Connect: `http://localhost:8083/connectors/product-cdc-connector/status`
- Elasticsearch: `http://localhost:9200`
- Postman Collection: Import [`postman_collection.json`](postman_collection.json) directly into Postman for end-to-end testing.

## Try the vertical slice

Create a product:

```powershell
$product = Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/products' `
  -ContentType 'application/json' `
  -Body '{"sku":"PHONE-15","title":"iPhone 15","description":"A smartphone","category":"Electronics","price":799.00,"stockQuantity":12,"active":true}'
$product
```

Search after Elasticsearch's refresh interval:

```powershell
Invoke-RestMethod 'http://localhost:8080/api/v1/search?q=iphne'
Invoke-RestMethod 'http://localhost:8080/api/v1/search?q=iph&category=Electronics&available=true&sort=PRICE_ASC'
```

Update with optimistic-version protection:

```powershell
$body = @{ price = 749.00; version = $product.version } | ConvertTo-Json
Invoke-RestMethod -Method Patch `
  -Uri "http://localhost:8080/api/v1/products/$($product.id)" `
  -ContentType 'application/json' -Body $body
```

Delete:

```powershell
Invoke-RestMethod -Method Delete -Uri "http://localhost:8080/api/v1/products/$($product.id)"
```

### Search Facets & Aggregations

Search responses include dynamic aggregations (`facets`) by category, price statistics (`min`, `max`, `avg`, `count`), and in-stock counts:

```powershell
Invoke-RestMethod 'http://localhost:8080/api/v1/search?category=Electronics&includeFacets=true'
```

### Full Reindexing & Zero-Downtime Migration

Stream all database products directly into Elasticsearch in batches and optionally switch the alias atomically:

```powershell
# Reindex into a new index 'products-v2' and atomically switch alias:
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/admin/reindex' `
  -ContentType 'application/json' `
  -Body '{"targetIndex":"products-v2","batchSize":500,"switchAlias":true}'

# Or manually point alias to a specific target index:
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/admin/switch-alias?targetIndex=products-v2'
```

### Dead-Letter Topic (DLT) Inspection & Replay

Inspect poisoned/unparseable messages and replay corrected messages back to the primary CDC topic:

```powershell
# Inspect dead-lettered messages with original failure details and exceptions:
Invoke-RestMethod 'http://localhost:8080/api/v1/admin/dlt?limit=50'

# Replay messages by offset or with a corrected payload back to the main topic:
Invoke-RestMethod -Method Post `
  -Uri 'http://localhost:8080/api/v1/admin/dlt/replay' `
  -ContentType 'application/json' `
  -Body '{"offsets":[0],"correctedValue":"{\"after\":{...},\"op\":\"c\"}"}'
```

## Tests

```powershell
.\mvnw.cmd clean test
```

Unit tests do not require Docker. The Testcontainers CDC integration test requires Docker, but no manually started services.

Run the real end-to-end CDC test with Docker available:

```powershell
.\mvnw.cmd clean verify -Pintegration
```

The integration test starts PostgreSQL with logical replication, Kafka, Kafka Connect with Debezium, and Elasticsearch. It then verifies API create, update, and delete operations through the complete CDC path and search API. No services need to be started manually.

## Operational notes

- The database schema is owned by a non-login group shared by the application and the limited replication user. The application does not connect as PostgreSQL superuser.
- Flyway owns schema changes; Hibernate runs with `ddl-auto=validate`.
- Debezium keeps the native envelope and emits `c`, `u`, `r`, `d`, followed by a null tombstone for a hard delete.
- The consumer retries failed records with bounded exponential backoff, then publishes to `inventory.public.products.DLT`.
- The explicit Elasticsearch mapping is created as `products-v1`, with the application reading and writing through alias `products`.
- For a mapping-breaking change, create `products-v2`, backfill it, validate it, and atomically move the alias.
- The consumer uses Spring Kafka batch listeners (`ack-mode: batch`, `max-poll-records: 500`) and indexes/deletes records using Elasticsearch's Bulk API (`client.bulk()`) for high ingestion throughput.
- End-to-end replication lag is measured from PostgreSQL WAL commit (`source.ts_ms`) to Elasticsearch indexing completion and exposed via Micrometer timer `products.cdc.lag`.
- Search query execution latency is tracked via Micrometer timer `products.search.duration`.
- A stopped or abandoned Debezium replication slot can retain PostgreSQL WAL. `max_slot_wal_keep_size=1024MB` is configured in Compose to protect PostgreSQL from filling disk if Kafka Connect is paused or disconnected. Monitor and deliberately remove unused slots.

See [REVIEWED_IMPLEMENTATION_ROADMAP.md](REVIEWED_IMPLEMENTATION_ROADMAP.md) for the remaining resilience, integration-test, reconciliation, observability, and benchmarking stages.

## Resetting local data

`docker compose down` preserves named volumes. To erase all local PostgreSQL, Kafka, and Elasticsearch data, run `docker compose down --volumes`. This is destructive and cannot be undone.

