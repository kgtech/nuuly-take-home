# Nuuly Inventory API, V2

V2 of the inventory service for the Nuuly Services assessment: it receives stock by SKU, processes purchases and lists inventory through the API in [`openapi.yaml`](openapi.yaml), and adds a React front end in [`frontend/`](frontend/). It is built with Java 25, Spring Boot 4.1.x (built with 4.1.1), PostgreSQL 18 and Redis 8.

The storage design is V2's own and is documented in [`DESIGN-V2.md`](DESIGN-V2.md): each SKU's stock is a row in Postgres, changed with a conditional `UPDATE` so concurrent purchases never oversell, every change is appended to a ledger the database keeps append-only, and Redis holds a copy of the most-read counts and of completed idempotent responses. Postgres is the source of truth for everything; Redis can be flushed, restarted or stopped at any moment without breaking a guarantee.

## Prerequisites

**To run it (reviewers):** Docker with Compose v2 and BuildKit; port 18080 free (or set `APP_PORT`); `curl` and `uuidgen` for the walk-through.

**To build and test it (developers):** JDK 25 (the Gradle wrapper, Gradle 9.1+, downloads Gradle and finds the toolchain), Docker (Testcontainers starts Postgres and Redis), Node 20+ and npm for the front end.

Versions: Java 25, Spring Boot 4.1.x (built with 4.1.1), springdoc-openapi 3.1.x (built with 3.1.1), Gradle 9.1+, PostgreSQL 18, Redis 8. Library versions live only in `gradle/libs.versions.toml`; the compose and Docker image tags repeat them (S10).

## Build and run

**Reviewers (Docker with Compose v2):**

```bash
docker compose up --build
# API:        http://localhost:18080/inventory
# Swagger UI: http://localhost:18080/swagger-ui.html
# Health:     http://localhost:18080/actuator/health  (/liveness, /readiness)
docker compose down -v   # stop and remove the database
```

This builds the app image, starts Postgres 18 and Redis 8, waits for both health checks and starts the app on host port 18080 (`APP_PORT` overrides it; the container listens on 8080). The non-default port keeps `docker compose up` clear of anything else on 8080. Postgres and Redis are published on loopback only, on random host ports (`docker compose port postgres 5432`, `docker compose port redis 6379`). Redis runs with no persistence and a 64 MB least-frequently-used cache limit, as a cache should. The app container has a readiness health check and an explicit heap policy (`-XX:MaxRAMPercentage=75.0`).

To wait until the app is ready:

```bash
docker compose up --build -d
until curl -sf localhost:18080/actuator/health/readiness >/dev/null; do sleep 2; done
```

**Development (JDK 25 + Docker):**

```bash
./gradlew clean build --warning-mode=fail   # compile with -Werror and run every test (Testcontainers: Postgres + Redis)
./gradlew bootRun                           # starts Postgres and Redis from compose.yaml, stops them on exit
docker compose up -d postgres redis         # the stores alone, e.g. for a debugger-launched app
```

An app started outside `bootRun` needs the stores' addresses:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$(docker compose port postgres 5432 | cut -d: -f2)/inventory"
export SPRING_DATASOURCE_USERNAME=inventory SPRING_DATASOURCE_PASSWORD=inventory
export SPRING_DATA_REDIS_PORT="$(docker compose port redis 6379 | cut -d: -f2)"
```

`compose.yaml` defines Postgres and Redis; `compose.override.yaml` adds the app. `bootRun` reads `compose.yaml` only. Don't run `docker compose up` and `bootRun` together: `bootRun` listens on 8080, the compose app on 18080, but both start the stores from compose.yaml.

**Front end** (see [`frontend/README.md`](frontend/README.md) and [`frontend/DECISIONS.md`](frontend/DECISIONS.md)):

```bash
cd frontend
npm ci
npm run dev          # http://localhost:15173, proxies /inventory to http://localhost:18080 (start the service first; VITE_PORT and API_URL override)
npm run lint && npm run typecheck && npm test && npm run build
npm run test:e2e     # Playwright against the real service on :18080 (add then purchase; a double submit changes stock once)
```

## Try it

Start from an empty database (`docker compose down -v && docker compose up --build`). Error bodies are `text/plain`; successful bodies are JSON.

```bash
curl -i localhost:18080/inventory                     # 200 []
curl -i -X POST localhost:18080/inventory/ABC-1 -H 'Content-Type: application/json' \
     -d '{"quantity":5}'                             # 200 {"skuId":"ABC-1","quantity":5}
curl -i localhost:18080/inventory/ABC-1               # 200 {"skuId":"ABC-1","quantity":5}   (cached for later reads)
curl -i -X POST localhost:18080/inventory/ABC-1/purchase -H 'Content-Type: application/json' \
     -d '{"quantity":2}'                             # 200 {"skuId":"ABC-1","quantity":3}
curl -i -X POST localhost:18080/inventory/ABC-1/purchase -H 'Content-Type: application/json' \
     -d '{"quantity":10}'                            # 400 Insufficient inventory
curl -i localhost:18080/inventory/NOPE                # 404 SKU not found
curl -i -X POST localhost:18080/inventory/ABC-1 -H 'Content-Type: application/json' \
     -d '{"quantity":0}'                             # 400 Invalid request
```

**Idempotency-Key.** Either POST accepts an optional `Idempotency-Key` UUID header. The same request with the same key within 24 hours returns the first response and changes stock once; the same key with a different SKU, endpoint or quantity, or a key older than 24 hours, returns 400 `Invalid request`:

```bash
KEY=$(uuidgen)
for q in 5 5 6; do
  curl -i -X POST localhost:18080/inventory/K-1 -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $KEY" -d "{\"quantity\":$q}"
done
# 200 {"skuId":"K-1","quantity":5}
# 200 {"skuId":"K-1","quantity":5}   replayed (from Redis when it has the key, else from Postgres)
# 400 Invalid request                 same key, different quantity
```

**Paging.** `GET /inventory` returns SKUs sorted by SKU ID, at most 250 per response, with a `Link: <url>; rel="next"` header while more follow; `limit` (1–250) sets a smaller page and `after` is the last SKU ID of the previous page:

```bash
curl -i 'localhost:18080/inventory?limit=2'
# 200 [{"skuId":"ABC-1","quantity":3},{"skuId":"K-1","quantity":5}]  (Link when more than 2 SKUs exist)
```

**v2: a SKU with details.** Three additive operations under `/v2/inventory` ([`DESIGN-V2.md`](DESIGN-V2.md) §8) give a SKU a name, description, cost and image URLs. The four operations above are unchanged; stock still changes only through them. Every `SkuItem` response carries an `ETag` (the details version, `"0"` before any details); `PUT` takes an optional `If-Match`. Errors are text/plain like v1, with two new fixed texts (409, 412) that say what to do next:

```bash
curl -i -X POST localhost:18080/v2/inventory/LN-1 -H 'Content-Type: application/json' \
     -d '{"details":{"name":"Linen shirt","description":"Long sleeve","cost":{"amount":12900,"currency":"USD"},
          "images":["https://cdn.example.com/a.jpg"]},"initialQuantity":5}'
# 201 ETag: "1"  {"skuId":"LN-1","quantity":5,"details":{"name":"Linen shirt",...}}   (one ledger row, reason add)
curl -i -X POST localhost:18080/v2/inventory/LN-1 -H 'Content-Type: application/json' \
     -d '{"details":{"name":"Other"}}'
# 409 SKU already exists. Set its details with PUT /v2/inventory/{skuId}; add stock with POST /inventory/{skuId}.
curl -i -X PUT localhost:18080/v2/inventory/LN-1 -H 'Content-Type: application/json' -H 'If-Match: "1"' \
     -d '{"name":"Linen shirt, navy","images":[]}'
# 200 ETag: "2"  {"skuId":"LN-1","quantity":5,"details":{"name":"Linen shirt, navy","description":"","images":[]}}
curl -i -X PUT localhost:18080/v2/inventory/LN-1 -H 'Content-Type: application/json' -H 'If-Match: "1"' \
     -d '{"name":"Stale edit"}'
# 412 Details changed since you read them. Reload the SKU and retry with its new ETag.
curl -i localhost:18080/v2/inventory/ABC-1              # 200 ETag: "0"  {"skuId":"ABC-1","quantity":3}   (no details yet)
curl -i 'localhost:18080/v2/inventory?limit=2'           # 200 [{"skuId":"ABC-1","quantity":3},{"skuId":"K-1",...}]  (Link as v1)
```

`POST /v2/inventory/{skuId}` honours `Idempotency-Key` like the spec POSTs: the whole request (details and initial quantity) is the fingerprint, and 201 and 409 are replayed. Field rules: `name` 1–120 characters, `description` up to 2,000, `cost.amount` an integer in minor units with a three-letter uppercase `cost.currency` (both or neither), up to 10 absolute http(s) `images` URLs of up to 2,048 characters, `initialQuantity` 0 to 2,147,483,647; a body above 32 KB is 400. Details are read from Postgres with the count in one join and are never cached in Redis.

## How V2 stores stock

Short version of [`DESIGN-V2.md`](DESIGN-V2.md):

- **Stock:** `sku.quantity` (`CHECK (quantity >= 0)`) and `sku.version`. A purchase is `UPDATE sku SET quantity = quantity - :q, version = version + 1 WHERE sku_id = :id AND quantity >= :q RETURNING ...` in a READ COMMITTED transaction; no row updated means "insufficient" (or "not found"). Concurrent writers on one SKU queue on the row lock; writers on different SKUs never interact. Adds are guarded the same way against overflowing a bigint.
- **Ledger:** every change also inserts an `inventory_ledger` row in the same transaction; a trigger makes `UPDATE`/`DELETE` on the ledger (and `DELETE` on `sku`) fail. A test asserts `quantity = SUM(quantity_delta)` for every SKU after the concurrency tests.
- **Idempotency:** the claim (`INSERT ... ON CONFLICT DO NOTHING`) and the stored response live in Postgres in the stock transaction, so a key changes stock at most once even for concurrent repeats. Redis keeps a copy of completed responses for 24 hours as a fast path.
- **Reads:** `GET /inventory/{skuId}` tries `stock:{skuId}` in Redis, else reads the row and caches it. Committed writes refresh an existing cache entry with a versioned Lua script, so an older value never overwrites a newer one. **Staleness bound:** a read is never older than `inventory.cache.stock-ttl` (default 5 s); in normal operation it is fresh within milliseconds of the commit.
- **Which SKUs are cached:** those read within the last TTL. An entry lives one TTL from the read that created it (a hit does not renew it; a write refreshes the value but not the TTL), so the hottest SKU costs Postgres one read per TTL. Writes never create entries. Redis's 64 MB LFU limit is a backstop shared with the replay copies.

**Failure behaviour.** Redis down, slow, flushed or restarted: every endpoint keeps its status codes and bodies; reads and replays fall through to Postgres; one WARN line per role per 10 s. A slow Redis adds at most one 250 ms timeout per Redis call (up to three on a keyed POST). `/actuator/health` names its components (`db`, `redis`) without details and reports DOWN while Redis is down, so it is an operator view, not a probe: point liveness and readiness probes at `/actuator/health/liveness` and `/actuator/health/readiness` (the readiness group is `readinessState` and `db`, and stays UP without Redis). Redis is a start-up dependency in compose (`depends_on: service_healthy`) only so the stack comes up in order; the app itself starts and runs without it. Postgres down: cached reads still answer until their TTL; everything else is 500 `Internal server error`, and readiness is DOWN. A crash between the Postgres commit and the Redis refresh leaves a cache entry that is stale for at most the TTL and an idempotency entry that is simply absent (the next repeat goes to Postgres).

**Operations.** `idempotency_keys` rows are kept (T1 needs them to reject an old key); their Redis copies expire after 24 h. To reclaim space, clear the stored `status`, `content_type` and `body` of rows older than 24 h (`UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL WHERE created_at < now() - interval '24 hours'`); a cleared row still rejects reuse with 400, whatever its age. The table has no index on `created_at`, so run that statement off-peak or add the index first; the freed space returns after VACUUM. Growth: one `inventory_ledger` row (about 60 bytes) per successful add or purchase, kept forever (the trigger forbids deletion; archive by partitioning if a year of traffic matters), and one `idempotency_keys` row (about 200 bytes with its body) per keyed request that passes validation, including 404s from unknown callers, since there is no authentication. Redis needs no persistence and no backup, and must be reachable only by the service: the app replays what it finds under `idem:` (validated against the row's allowed statuses and content types) and trusts `stock:` counts for one TTL, so a writer on the Redis port could inject a response or a count; keep it on loopback or a private network, or enable AUTH.

## API docs

Swagger UI at `/swagger-ui.html`, OpenAPI at `/v3/api-docs` and `/v3/api-docs.yaml`. [`openapi.yaml`](openapi.yaml) is the committed export; `ApiDocsTest` regenerates it and fails when the code and the file differ. The docs list exactly the spec's status codes per operation, with `text/plain` errors, plus `GET /inventory`'s paging parameters and its 400 for an undecodable or repeated `after`.

## Assumptions

The spec leaves these open; V2 keeps the first build's answers ([`DECISIONS.md`](DECISIONS.md), IDs in parentheses) except where [`DESIGN-V2.md`](DESIGN-V2.md) §7 supersedes them:

- SKU IDs are case-sensitive, 1–64 characters, `[A-Za-z0-9][A-Za-z0-9._-]*`; creating another ID (spec or v2, and a v2 PUT) is 400, reading or purchasing it is 404 (G1, G11). An encoded slash is part of the ID (`/inventory/A%2FB` → 404) (C1).
- Stock is a 64-bit integer; an add that would overflow returns 400 and changes nothing (G2, G12). A request adds or purchases at most 2,147,483,647 (V2).
- Malformed JSON (including trailing tokens), a missing body, a body above 4 KB, a wrong Content-Type, a non-integer `quantity`, a raw `;` in the SKU segment, or an Accept whose most specific range for JSON has q=0 on POST return 400; unknown fields are ignored; GET ignores Accept (G3, G13, U2, C3). An undecodable query string (`?x=%zz`) is 400 only where the query is read (`GET /inventory`, `/actuator/**`, springdoc); the item GET and the POSTs ignore it (C1, Z3).
- A SKU sold to 0 keeps its row and stays listed (G5). Error bodies are exactly `SKU not found`, `Insufficient inventory`, `Invalid request` or `Internal server error` (G6); the v2 operations add the fixed 409 and 412 texts shown above (A23, A24).
- v2 details are a separate row per SKU; a `POST /v2` on an existing SKU is 409 rather than an upsert, a `PUT` without `If-Match` is last-write-wins, and a `PUT` never changes stock (DESIGN-V2 §8, A21–A29).
- Concurrent purchases never oversell, however many app instances run: the conditional `UPDATE` and the `CHECK` enforce it in Postgres (G7 as superseded).
- `Idempotency-Key` is optional and must be a UUID; a repeated request replays the first response, including 404 and 400 outcomes; a different body, SKU or endpoint, or a key older than 24 h, returns 400 (G8, G14, R1, S3, T1, U1, Y3). Two simultaneous requests with the same key produce one change (R2).
- The list is sorted by SKU ID with at most 250 per response and a `Link` to the next page built from the request's `Host` (no `X-Forwarded-*` handling); a bad `limit` is ignored, a repeated `after` or an undecodable query is 400 (G9, R4, R8, Z3, C2).
- No authentication (G10). Requests outside the spec's operations get standard codes with the reason phrase as text; `/actuator/**` and the springdoc paths keep Spring Boot's own responses, except Tomcat-level rejections and undecodable queries, which are text/plain everywhere (S6, T3, C1).
- A cached count may lag a committed write by up to `inventory.cache.stock-ttl` (5 s, measured from the read that cached it) if the post-commit refresh fails or raced a read miss; otherwise reads are fresh within milliseconds (DESIGN-V2 §3).
- The app is published on 0.0.0.0:18080 with no authentication (G10) so reviewers can reach it; Postgres and Redis are on loopback. `-XX:MaxRAMPercentage=75.0` is relative to the container's memory limit, so set one (`mem_limit`) in a real deployment.

## AI use

V2 was built in one autonomous run by the Fable model from a warm-start package produced by a retrospective of the first build: [`PROMPT.md`](PROMPT.md) (the instructions), [`spec/`](spec/), [`DECISIONS.md`](DECISIONS.md), [`CLAUDE.md`](CLAUDE.md), [`issues.md`](issues.md), [`lessons.md`](lessons.md). The run's own records are [`.fable/log.md`](.fable/log.md), [`.fable/current-implementation.md`](.fable/current-implementation.md), [`.fable/plan.md`](.fable/plan.md), [`DESIGN-V2.md`](DESIGN-V2.md), [`DECISIONS-ADDED.md`](DECISIONS-ADDED.md), [`DEVIATIONS.md`](DEVIATIONS.md), [`frontend/DECISIONS.md`](frontend/DECISIONS.md), the self-critique in `.fable/critique.md` and `.fable/interview-defense.md`, and [`FABLE_REPORT.md`](FABLE_REPORT.md).
