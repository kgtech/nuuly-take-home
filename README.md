# Nuuly Inventory API, V2


**Decisions:** the decision board for this build is a private artifact, https://claude.ai/artifact/5SCRVQ6fveSeN3TfbpQDAG (main's board is separate and read only). [`DECISIONS.md`](DECISIONS.md) and [`CLAUDE.md`](CLAUDE.md) are generated from it (`ai/export-board.mjs`); its database is dumped in [`ai/final/board-db/`](ai/final/board-db/) so the exports can be reproduced without the artifact.
V2 of the inventory service for the Nuuly Services assessment: it receives stock by SKU, processes purchases and lists inventory through the API in [`openapi.yaml`](openapi.yaml), and adds a React front end in [`frontend/`](frontend/). It is built with Java 25, Spring Boot 4.1.x (built with 4.1.1) and PostgreSQL 18.

The storage design is V2's own and is documented in [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md): each SKU's stock is a row in Postgres, changed with a conditional `UPDATE` so concurrent purchases never oversell, every change is appended to a ledger the database keeps append-only, and idempotent responses are stored beside the claim that made them. Postgres is the only store; a cache was part of the first V2 design and was removed (DESIGN-V2 §9).

## Prerequisites

**To run it (reviewers):** Docker with Compose v2 and BuildKit; port 8080 free (or set `APP_PORT`); `curl` and `uuidgen` for the walk-through.

**To build and test it (developers):** JDK 25 (the Gradle wrapper, Gradle 9.1+, downloads Gradle and finds the toolchain), Docker (Testcontainers starts Postgres), Node 20+ and npm for the front end.

Versions: Java 25, Spring Boot 4.1.x (built with 4.1.1), springdoc-openapi 3.1.x (built with 3.1.1), Gradle 9.1+, PostgreSQL 18. Library versions live only in `gradle/libs.versions.toml`; the compose and Docker image tags repeat them (S10).

## Build and run

**Reviewers (Docker with Compose v2):**

```bash
docker compose up --build
# API:        http://localhost:8080/inventory
# Swagger UI: http://localhost:8080/swagger-ui.html
# Health:     http://localhost:8080/actuator/health  (/liveness, /readiness)
docker compose down -v   # stop and remove the database
```

This builds the app image, starts Postgres 18, waits for its health check and starts the app on host port 8080 (`APP_PORT` overrides it; the container listens on 8080). If 8080 is taken, set `APP_PORT`. Postgres is published on loopback only, on a random host port (`docker compose port postgres 5432`). The app container has a readiness health check and an explicit heap policy (`-XX:MaxRAMPercentage=75.0`).

To wait until the app is ready:

```bash
docker compose up --build -d
until curl -sf localhost:8080/actuator/health/readiness >/dev/null; do sleep 2; done
```

**Development (JDK 25 + Docker):**

```bash
./gradlew clean build --warning-mode=fail   # compile with -Werror and run every test (Testcontainers: Postgres)
./gradlew bootRun                           # starts Postgres from compose.yaml, stops it on exit
docker compose up -d postgres               # the database alone, e.g. for a debugger-launched app
```

An app started outside `bootRun` needs the database's address:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$(docker compose port postgres 5432 | cut -d: -f2)/inventory"
export SPRING_DATASOURCE_USERNAME=inventory SPRING_DATASOURCE_PASSWORD=inventory
```

`compose.yaml` defines Postgres; `compose.override.yaml` adds the app. `bootRun` reads `compose.yaml` only. Don't run `docker compose up` and `bootRun` together: both want host port 8080 and both start the database from compose.yaml.

**Front end** (see [`frontend/README.md`](frontend/README.md) and [`frontend/DECISIONS.md`](frontend/DECISIONS.md)):

```bash
cd frontend
npm ci
npm run dev          # http://localhost:5173, proxies /v2 to http://localhost:8080 (start the service first; VITE_PORT and API_URL override)
npm run lint && npm run typecheck && npm test && npm run build
npm run test:e2e     # Playwright against the real service on :8080 (add then purchase; a double submit changes stock once)
```

## Try it

Start from an empty database (`docker compose down -v && docker compose up --build`). Error bodies are `text/plain`; successful bodies are JSON.

```bash
curl -i localhost:8080/inventory                     # 200 []
curl -i -X POST localhost:8080/inventory/ABC-1 -H 'Content-Type: application/json' \
     -d '{"quantity":5}'                             # 200 {"skuId":"ABC-1","quantity":5}
curl -i localhost:8080/inventory/ABC-1               # 200 {"skuId":"ABC-1","quantity":5}
curl -i -X POST localhost:8080/inventory/ABC-1/purchase -H 'Content-Type: application/json' \
     -d '{"quantity":2}'                             # 200 {"skuId":"ABC-1","quantity":3}
curl -i -X POST localhost:8080/inventory/ABC-1/purchase -H 'Content-Type: application/json' \
     -d '{"quantity":10}'                            # 400 Insufficient inventory
curl -i localhost:8080/inventory/NOPE                # 404 SKU not found
curl -i -X POST localhost:8080/inventory/ABC-1 -H 'Content-Type: application/json' \
     -d '{"quantity":0}'                             # 400 Invalid request
```

**Idempotency-Key.** Either POST accepts an optional `Idempotency-Key` UUID header. The same request with the same key within 24 hours returns the first response and changes stock once; the same key with a different SKU, endpoint or quantity, or a key older than 24 hours, returns 400 `Invalid request`:

```bash
KEY=$(uuidgen)
for q in 5 5 6; do
  curl -i -X POST localhost:8080/inventory/K-1 -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $KEY" -d "{\"quantity\":$q}"
done
# 200 {"skuId":"K-1","quantity":5}
# 200 {"skuId":"K-1","quantity":5}   replayed from the stored row
# 400 Invalid request                 same key, different quantity
```

**Paging.** `GET /inventory` returns SKUs sorted by SKU ID, at most 250 per response, with a `Link: <url>; rel="next"` header while more follow; `limit` (1–250) sets a smaller page and `after` is the last SKU ID of the previous page:

```bash
curl -i 'localhost:8080/inventory?limit=2'
# 200 [{"skuId":"ABC-1","quantity":3},{"skuId":"K-1","quantity":5}]  (Link when more than 2 SKUs exist)
```

**v2: a SKU with details.** Three additive operations under `/v2/inventory` ([`DESIGN-V2.md`](ai/v2/DESIGN-V2.md) §8) give a SKU a name, description, cost and image URLs. The four operations above are unchanged; stock still changes only through them. Every `SkuItem` response carries an `ETag` (the details version, `"0"` before any details); `PUT` takes an optional `If-Match`. Errors are text/plain like v1, with two new fixed texts (409, 412) that say what to do next:

```bash
curl -i -X POST localhost:8080/v2/inventory/LN-1 -H 'Content-Type: application/json' \
     -d '{"details":{"name":"Linen shirt","description":"Long sleeve","cost":{"amount":12900,"currency":"USD"},
          "images":["https://cdn.example.com/a.jpg"]},"initialQuantity":5}'
# 201 ETag: "1"  {"skuId":"LN-1","quantity":5,"details":{"name":"Linen shirt",...}}   (one ledger row, reason add)
curl -i -X POST localhost:8080/v2/inventory/LN-1 -H 'Content-Type: application/json' \
     -d '{"details":{"name":"Other"}}'
# 409 SKU already exists. Set its details with PUT /v2/inventory/{skuId}; add stock with POST /inventory/{skuId}.
curl -i -X PUT localhost:8080/v2/inventory/LN-1 -H 'Content-Type: application/json' -H 'If-Match: "1"' \
     -d '{"name":"Linen shirt, navy","images":[]}'
# 200 ETag: "2"  {"skuId":"LN-1","quantity":5,"details":{"name":"Linen shirt, navy","description":"","images":[]}}
curl -i -X PUT localhost:8080/v2/inventory/LN-1 -H 'Content-Type: application/json' -H 'If-Match: "1"' \
     -d '{"name":"Stale edit"}'
# 412 Details changed since you read them. Reload the SKU and retry with its new ETag.
curl -i localhost:8080/v2/inventory/ABC-1              # 200 ETag: "0"  {"skuId":"ABC-1","quantity":3}   (no details yet)
curl -i 'localhost:8080/v2/inventory?limit=2'           # 200 [{"skuId":"ABC-1","quantity":3},{"skuId":"K-1",...}]  (Link as v1)
```

`POST /v2/inventory/{skuId}` honours `Idempotency-Key` like the spec POSTs: the whole request (details and initial quantity) is the fingerprint, and 201 and 409 are replayed. Field rules: `name` 1–120 characters, `description` up to 2,000, `cost.amount` an integer in minor units with a three-letter uppercase `cost.currency` (both or neither), up to 10 absolute http(s) `images` URLs of up to 2,048 characters, `initialQuantity` 0 to 2,147,483,647; a body above 64 KB is 400. Details are read from Postgres with the count in one join.

## How V2 stores stock

Short version of [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md):

- **Stock:** `sku.quantity` (`CHECK (quantity >= 0)`) and `sku.version`. A purchase is `UPDATE sku SET quantity = quantity - :q, version = version + 1 WHERE sku_id = :id AND quantity >= :q RETURNING ...` in a READ COMMITTED transaction; no row updated means "insufficient" (or "not found"). Concurrent writers on one SKU queue on the row lock; writers on different SKUs never interact. Adds are guarded the same way against overflowing a bigint.
- **Ledger:** every change also inserts an `inventory_ledger` row in the same transaction; a trigger makes `UPDATE`/`DELETE` on the ledger (and `DELETE` on `sku`) fail. A test asserts `quantity = SUM(quantity_delta)` for every SKU after the concurrency tests.
- **Idempotency:** the claim (`INSERT ... ON CONFLICT DO NOTHING`) and the stored response live in Postgres in the stock transaction, so a key changes stock at most once even for concurrent repeats.
- **Reads:** `GET /inventory/{skuId}` reads the `sku` row: one primary-key lookup at READ COMMITTED autocommit, which never waits on a writer, so a read is always the last committed count. There is no cache (DESIGN-V2 §9).

**Failure behaviour.** Postgres down: reads and writes answer 500 `Internal server error`, nothing is acknowledged, and `/actuator/health/readiness` reports DOWN. A crash inside a transaction rolls it back (no stock change, no ledger row, no claim); a client retry with the same key claims afresh. `/actuator/health` names its component (`db`) without details.

**Operations.** `idempotency_keys` rows are kept (T1 needs them to reject an old key). To reclaim space, clear the stored `status`, `content_type` and `body` of rows older than 24 h (`UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL WHERE created_at < now() - interval '24 hours'`); a cleared row still rejects reuse with 400, whatever its age. The table has no index on `created_at`, so run that statement off-peak or add the index first; the freed space returns after VACUUM. Growth: one `inventory_ledger` row (about 60 bytes) per successful add or purchase, kept forever (the trigger forbids deletion; archive by partitioning if a year of traffic matters), and one `idempotency_keys` row (about 200 bytes with its body) per keyed request that passes validation, including 404s from unknown callers, since there is no authentication.

## API docs

Swagger UI at `/swagger-ui.html`, OpenAPI at `/v3/api-docs` and `/v3/api-docs.yaml`. [`openapi.yaml`](openapi.yaml) is the committed export; `ApiDocsTest` regenerates it and fails when the code and the file differ. The docs list exactly the spec's status codes per operation, with `text/plain` errors, plus `GET /inventory`'s paging parameters and its 400 for an undecodable or repeated `after`.

## Assumptions

The spec leaves these open; V2 keeps the first build's answers ([`ai/v2/DECISIONS-v2.md`](ai/v2/DECISIONS-v2.md), IDs in parentheses) except where [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md) §7 supersedes them:

- SKU IDs are case-sensitive, 1–64 characters, `[A-Za-z0-9][A-Za-z0-9._-]*`; creating another ID (spec or v2, and a v2 PUT) is 400, reading or purchasing it is 404 (G1, G11). An encoded slash is part of the ID (`/inventory/A%2FB` → 404) (C1).
- Stock is a 64-bit integer; an add that would overflow returns 400 and changes nothing (G2, G12). A request adds or purchases at most 2,147,483,647 (V2).
- Malformed JSON (including trailing tokens), a missing body, a body above 4 KB, a wrong Content-Type, a non-integer `quantity`, a raw `;` in the SKU segment, or an Accept whose most specific range for JSON has q=0 on POST return 400; unknown fields are ignored; GET ignores Accept (G3, G13, U2, C3). An undecodable query string (`?x=%zz`) is 400 only where the query is read (`GET /inventory`, `/actuator/**`, springdoc); the item GET and the POSTs ignore it (C1, Z3).
- A SKU sold to 0 keeps its row and stays listed (G5). Error bodies are exactly `SKU not found`, `Insufficient inventory`, `Invalid request` or `Internal server error` (G6); the v2 operations add the fixed 409 and 412 texts shown above (A23, A24).
- v2 details are a separate row per SKU; a `POST /v2` on an existing SKU is 409 rather than an upsert, a `PUT` without `If-Match` is last-write-wins, and a `PUT` never changes stock (DESIGN-V2 §8, A21–A29).
- Concurrent purchases never oversell, however many app instances run: the conditional `UPDATE` and the `CHECK` enforce it in Postgres (G7 as superseded).
- `Idempotency-Key` is optional and must be a UUID; a repeated request replays the first response, including 404 and 400 outcomes; a different body, SKU or endpoint, or a key older than 24 h, returns 400 (G8, G14, R1, S3, T1, U1, Y3). Two simultaneous requests with the same key produce one change (R2).
- The list is sorted by SKU ID with at most 250 per response and a `Link` to the next page built from the request's `Host` (no `X-Forwarded-*` handling); a bad `limit` is ignored, a repeated `after` or an undecodable query is 400 (G9, R4, R8, Z3, C2).
- No authentication (G10). Requests outside the spec's operations get standard codes with the reason phrase as text; `/actuator/**` and the springdoc paths keep Spring Boot's own responses, except Tomcat-level rejections and undecodable queries, which are text/plain everywhere (S6, T3, C1).
- A read is always the last committed count; there is no cache (DESIGN-V2 §9).
- The app is published on 0.0.0.0:8080 with no authentication (G10) so reviewers can reach it; Postgres is on loopback. `-XX:MaxRAMPercentage=75.0` is relative to the container's memory limit, so set one (`mem_limit`) in a real deployment.

## Designed, not built

Both are deferred future improvements, recorded with their reasons, revisit triggers and proposed approach. Neither changes the API described above.

- **Store a domain outcome instead of the HTTP response against an Idempotency-Key** ([#83](https://github.com/kgtech/nuuly-take-home/issues/83), A35, [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md) §11). Not built because every keyed caller today is the HTTP controller, so it would change no behaviour. It would also trade byte-for-byte replays (Y4) for re-rendered ones, and it needs a two-format migration on a table whose rows are never purged (R9).
- **Messaging: an outbox to Kafka for ERP sync, high throughput and flash sales** ([#85](https://github.com/kgtech/nuuly-take-home/issues/85), A36, [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md) §11). Not built because:
  - the spec's operations answer synchronously with fixed status codes (G10), so a queue in front of writes would change the contract;
  - the no-oversell decision and exactly-once already hold in one Postgres transaction, and a broker would add a dual write plus at-least-once delivery that still needs the idempotency claim;
  - no ERP or other consumer exists in this scope;
  - hot-SKU throughput has not been measured.

  The first step when it is picked up is a load test. After that, events go out through a transactional outbox with no contract change; ERP adjustments come in through a consumer keyed by message ID (after #83); and flash-sale options are applied from least to most contract change.

## AI use

V2 was built in one autonomous run by the Fable model from a warm-start package produced by a retrospective of the first build: [`PROMPT.md`](ai/v2/PROMPT.md) (the instructions), [`spec/`](docs/), [`ai/v2/DECISIONS-v2.md`](ai/v2/DECISIONS-v2.md), [`ai/v2/CLAUDE-v2.md`](ai/v2/CLAUDE-v2.md), [`issues.md`](ai/v2/issues.md), [`lessons.md`](ai/v2/lessons.md). The run's own records are [`ai/v2/run-records/log.md`](ai/v2/run-records/log.md), [`ai/v2/run-records/current-implementation.md`](ai/v2/run-records/current-implementation.md), [`ai/v2/run-records/plan.md`](ai/v2/run-records/plan.md), [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md), [`DECISIONS-ADDED.md`](ai/v2/DECISIONS-ADDED.md), [`DEVIATIONS.md`](ai/v2/DEVIATIONS.md), [`frontend/DECISIONS.md`](frontend/DECISIONS.md), the self-critique in `ai/v2/run-records/critique.md` and `ai/v2/run-records/interview-defense.md`, and [`FABLE_REPORT.md`](ai/v2/REPORT.md).
