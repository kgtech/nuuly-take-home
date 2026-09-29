# Nuuly Inventory API

Inventory service for the Nuuly Services assessment ([`spec`](docs/NUULY-ASSESSMENT-README-JUL-2026.md)): it receives stock by SKU, processes purchases and lists inventory, backed by PostgreSQL 18 and built with Java 25 and Spring Boot 4.1.x (built with 4.1.1). The spec's four operations are served unversioned at `/inventory`, exactly as the spec describes them plus two recorded deviations (see [Deviations](#deviations)): `GET /inventory` returns a fixed page of 250 SKUs with an `after` cursor, and any `Idempotency-Key` on an unversioned POST is rejected with 400. A client that ignores the `Link` header sees 250 of N SKUs and no other sign that the list was cut; everything the spec did not ask for (idempotency-keyed writes, page-size control, SKU details) lives under `/v2`. Both versions read and write the same stock. A React front end in [`frontend/`](frontend/) uses `/v2` only. The design is in [`DESIGN.md`](DESIGN.md); the decisions behind it are in [`DECISIONS.md`](DECISIONS.md) and [`CLAUDE.md`](CLAUDE.md), which are generated from a decision board (the board is a private artifact, https://claude.ai/artifact/5SCRVQ6fveSeN3TfbpQDAG; its database is dumped in [`ai/final/board-db/`](ai/final/board-db/) so the exports can be reproduced with `ai/export-board.mjs`). No CI has run on GitHub for this branch: the token lacks the `workflow` scope, so the workflow is parked (deviation D-1) and `scripts/gate.sh --e2e` is the record.

## Prerequisites

**To run it (reviewers):** Docker with Compose v2 and BuildKit; port 8080 free (or set `APP_PORT`); `curl` and `uuidgen` for the walk-through.

**To build and test it (developers):** JDK 25 (the Gradle wrapper, Gradle 9.1+, downloads Gradle and finds the toolchain), Docker (Testcontainers starts Postgres), Node 20+ and npm for the front end.

Versions: Java 25, Spring Boot 4.1.x (built with 4.1.1), springdoc-openapi 3.1.x (built with 3.1.1), Gradle 9.1+, PostgreSQL 18. Library versions live only in `gradle/libs.versions.toml`; the compose and Docker image tags repeat them.

## Run the service

```bash
docker compose up --build
# API:        http://localhost:8080/inventory  and  http://localhost:8080/v2/inventory
# Swagger UI: http://localhost:8080/swagger-ui.html
# Health:     http://localhost:8080/actuator/health  (/liveness, /readiness)
docker compose down -v   # stop and remove the database
```

This builds the app image, starts Postgres 18, waits for its health check and starts the app on host port 8080 (`APP_PORT=9090 docker compose up --build` changes the host port; the container always listens on 8080). Postgres is published on loopback only, on a random host port (`docker compose port postgres 5432`). The app container has a readiness health check and an explicit heap policy (`-XX:MaxRAMPercentage=75.0`). To wait until the app is ready when running detached:

```bash
docker compose up --build -d --wait
until curl -sf localhost:8080/actuator/health/readiness >/dev/null; do sleep 2; done
```

**Development (JDK 25 + Docker):**

```bash
./gradlew bootRun              # starts Postgres from compose.yaml, stops it on exit; app on :8080
docker compose up -d postgres  # the database alone, e.g. for a debugger-launched app
```

An app started outside `bootRun` needs the database's address:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$(docker compose port postgres 5432 | cut -d: -f2)/inventory"
export SPRING_DATASOURCE_USERNAME=inventory SPRING_DATASOURCE_PASSWORD=inventory
```

`compose.yaml` defines Postgres; `compose.override.yaml` adds the app. `bootRun` reads `compose.yaml` only. Don't run `docker compose up` and `bootRun` together: both want host port 8080.

## Run the front end

See [`frontend/README.md`](frontend/README.md) and [`frontend/DECISIONS.md`](frontend/DECISIONS.md). Start the service first, then:

```bash
cd frontend
npm ci
npm run dev          # http://localhost:5173, proxies /v2 to http://localhost:8080 (VITE_PORT and API_URL override)
npm run test:e2e     # Playwright against the real service through the dev proxy (run `npx playwright install chromium` once)
```

The front end calls only `/v2`: it lists, views, creates and edits SKUs with their details, and adds and purchases stock with an `Idempotency-Key` created once per user action.

## Run all the tests

```bash
./gradlew build      # compiles with -Werror and runs every service test (Testcontainers: Postgres); also checks the committed OpenAPI export
cd frontend && npm ci && npm run lint && npm run typecheck && npm run check:api && npm test && npm run build
cd frontend && npm run test:e2e          # Playwright, with the service running (see above)
scripts/gate.sh [--e2e]                  # all of the above; --e2e also starts the compose stack and runs Playwright
```

**Last green run.** `scripts/gate.sh --e2e` on the final tip on 2026-09-29: 998 service tests, 257 front-end tests and 48 Playwright tests passed (the code is the tip after the critique fixes #117 and #118; the last commits are docs and board only). No CI runs it yet: the workflow is parked at [`ai/final/ci-workflow.yml`](ai/final/ci-workflow.yml) until the GitHub token has the `workflow` scope.

**What the tests protect, and what they skip.**

- Every SQL statement and constraint runs against a real Postgres (Testcontainers), never a stand-in; concurrency tests (at most 8 threads) assert no oversell across both versions, and a per-test check asserts `quantity = SUM(ledger)`.
- ArchUnit rules keep the layout honest because rules written only as text were broken twice before: the raw request path is read by one class (L19), one test annotation and one container serve every Spring test (L27), and the unversioned controller never reaches idempotency code (L31). See [`ai/final/lessons.md`](ai/final/lessons.md).
- The critique's scratch mutation run changed 57 pieces of Java behaviour and the suite failed for 52; the survivors are gaps in test coverage, not defects, and test-only fixes close them. Not covered: load and performance (none measured), a real browser other than Chromium, and anything behind a proxy.

`scripts/gate.sh` accepts `--service-only` and `--frontend-only`; it uses `APP_PORT` (default 8080) and `VITE_PORT` (default 5173) and, with `--e2e`, stops if either port is busy. The same steps are written as a GitHub Actions workflow that is parked at [`ai/final/ci-workflow.yml`](ai/final/ci-workflow.yml) (see [Deviations](#deviations)), so the gate is the local one.

**Regenerate the board export.** `DECISIONS.md` and `CLAUDE.md` come from the board; run from `frontend/`, where Playwright is installed (the script resolves `playwright` from its own directory, so it is fed through stdin), and use a date to stamp the header (today's regenerates; the committed file's date reproduces it byte for byte):

```bash
cd frontend && npm ci && npx playwright install chromium
node --input-type=module - ../ai/decision-board.html ../ai/final/board-db /tmp/board-out 2026-09-29 < ../ai/export-board.mjs
```

## API versions

**Policy (OD-2).** The unversioned API is the spec and is frozen to it: an extension never lands there. `/v2` may grow compatibly (new operations, new optional fields, new optional headers); a change that would break a `/v2` caller gets a new prefix, `/v3`. Both versions read and write the same rows: a SKU added through one is visible through the other, and concurrent purchases through both never oversell.

| | Unversioned (`/inventory`), the spec | `/v2/inventory` |
|---|---|---|
| Read one | `GET /inventory/{skuId}` → `{skuId, quantity}` | `GET /v2/inventory/{skuId}` → `{skuId, quantity, details?}` with an `ETag` (the details version, `"0"` before any details) and `Cache-Control: no-store` |
| Add stock | `POST /inventory/{skuId}`, creates the SKU; no key | `POST /v2/inventory/{skuId}`, `Idempotency-Key` required; a repeat replays the first response |
| Purchase | `POST /inventory/{skuId}/purchase`; no key | `POST /v2/inventory/{skuId}/purchase`, `Idempotency-Key` required |
| List | `GET /inventory`: a fixed page of 250 and an `after` cursor; `limit` is ignored | `GET /v2/inventory`: `limit` 1–250 (default 250) and `after`; items carry their details where a SKU has them |
| Details | not offered | `PUT /v2/inventory/{skuId}/details`: create (201) or replace (200) a SKU's name, description, cost and images, with `If-Match` or `If-None-Match: *`; never changes stock |
| Errors | `text/plain`: `SKU not found`, `Insufficient inventory`, `Invalid request`, `Internal server error` | the same, plus 412 `Details changed since you read them. Reload the SKU and retry with its new ETag.` on the details PUT |

The `/v2` `ETag` is the details validator only: it does not change when the quantity changes, which deviates from RFC 9110 §8.8.1 (a strong validator covers the whole representation) and §9.3.4 (a validator on a PUT response). It is harmless while responses are `no-store` and a conditional GET is ignored, and it is the owner's contract (Target API in [`ai/final/PROMPT.md`](ai/final/PROMPT.md)); honouring `If-None-Match` on GET would need a separate details resource or `/v3`.

**Unversioned POSTs are not retry-safe, and they reject the key.** A request to `POST /inventory/{skuId}` or `…/purchase` that carries an `Idempotency-Key` header, with any value including an empty one, gets 400 `Invalid request` and changes nothing. That is deliberate: an endpoint that cannot deduplicate must not look as if it can, so a caller that needs retries uses `/v2`. Retrying an unversioned POST after a lost response adds or purchases again.

**Reaching every SKU.**

- **Unversioned:** `GET /inventory` returns the first 250 SKUs sorted by `skuId`; while more exist the response has `Link: <…/inventory?after=LAST>; rel="next"`. Follow the `Link` until a page has none. `GET /inventory/{skuId}` reads one.
- **`/v2`:** the same walk with `GET /v2/inventory`, whose `Link` carries `limit` and `after`; `limit` (1–250) shrinks the page, and items carry their details where a SKU has them. `GET /v2/inventory/{skuId}` reads one.
- Walking either list visits the same `(skuId, quantity)` pairs in the same order (a test asserts it), and a purchase through one version is visible through the other.

## API docs

Swagger UI at `/swagger-ui.html` shows both versions. There are two OpenAPI documents: [`openapi.yaml`](openapi.yaml) is the unversioned API (1.0.0) and `openapi-v2.yaml` the `/v2` API (2.0.0); the groups are named `inventory` and `inventory-v2`, and the running service serves each as YAML at `/v3/api-docs.yaml/{group}` (and as JSON under `/v3/api-docs/{group}`). Both files are committed exports that a test regenerates and fails on when the code and the file differ, and a conformance test compares `openapi.yaml` with the YAML block in the spec, allowing only the differences listed in the test (each with its decision ID). The front end's types are generated from `openapi-v2.yaml` only, so a call to an unversioned path fails the type check.

## Try it

Start from an empty database (`docker compose down -v && docker compose up --build`). Error bodies are `text/plain`; successful bodies are JSON. Outputs below were produced by these commands against the running stack; `Link` values show the default port.

```bash
API=http://localhost:${APP_PORT:-8080}   # once per shell; every command below uses $API
```

### The spec API (unversioned)

```bash
curl -i $API/inventory                     # 200 []
curl -i -X POST $API/inventory/ABC-1 -H 'Content-Type: application/json' \
     -d '{"quantity":5}'                             # 200 {"skuId":"ABC-1","quantity":5}
curl -i $API/inventory/ABC-1               # 200 {"skuId":"ABC-1","quantity":5}
curl -i -X POST $API/inventory/ABC-1/purchase -H 'Content-Type: application/json' \
     -d '{"quantity":2}'                             # 200 {"skuId":"ABC-1","quantity":3}
curl -i -X POST $API/inventory/ABC-1/purchase -H 'Content-Type: application/json' \
     -d '{"quantity":10}'                            # 400 Insufficient inventory
curl -i $API/inventory/NOPE                # 404 SKU not found
curl -i -X POST $API/inventory/ABC-1 -H 'Content-Type: application/json' \
     -d '{"quantity":0}'                             # 400 Invalid request
```

An unversioned POST with an `Idempotency-Key` is rejected and writes nothing (OD-4):

```bash
curl -i -X POST $API/inventory/ABC-1 -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" -d '{"quantity":5}'    # 400 Invalid request; ABC-1 is still 3
```

### The `/v2` API

Add and purchase require a UUID `Idempotency-Key`. The same key with the same request within 24 hours replays the first response and changes stock once; the same key with a different SKU, operation or quantity, or a key older than 24 hours, is 400 `Invalid request`; a missing or malformed key is 400 as well. A client without UUID support generates any random UUID v4 for each user action. The front end keeps its key in memory, so its retry protection lasts as long as the browser tab. Replayed bodies are point-in-time snapshots: the stored 200 shows the quantity and details the first request saw, so `GET` before you send an `If-Match`:

```bash
KEY=$(uuidgen)
for q in 5 5 6; do
  curl -i -X POST $API/v2/inventory/K-1 -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $KEY" -d "{\"quantity\":$q}"
done
# 200 {"skuId":"K-1","quantity":5}
# 200 {"skuId":"K-1","quantity":5}   replayed from the stored row
# 400 Invalid request                 same key, different quantity
curl -i -X POST $API/v2/inventory/K-1 -H 'Content-Type: application/json' \
     -d '{"quantity":5}'                                  # 400 Invalid request (no key)
curl -i -X POST $API/v2/inventory/K-1/purchase -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" -d '{"quantity":2}' # 200 {"skuId":"K-1","quantity":3}
curl -i -X POST $API/v2/inventory/K-1/purchase -H 'Content-Type: application/json' \
     -H "Idempotency-Key: $(uuidgen)" -d '{"quantity":20}' # 400 Insufficient inventory
```

**Details.** `PUT /v2/inventory/{skuId}/details` sets a SKU's details and never changes its stock. If the SKU does not exist it is created at quantity 0 (201, `ETag: "1"`); `If-None-Match: *` makes that create refuse to overwrite an existing SKU (412). If it exists the details are replaced (200, a new `ETag`); `If-Match` makes the replace succeed only against the tag you read (412 otherwise, including any `If-Match` on a SKU that does not exist yet). The idempotency key is ignored here: PUT is idempotent by method.

```bash
curl -i -X PUT $API/v2/inventory/LN-1/details -H 'Content-Type: application/json' -H 'If-None-Match: *' \
     -d '{"name":"Linen shirt","description":"Long sleeve","cost":{"amount":12900,"currency":"USD"},
          "images":["https://cdn.example.com/a.jpg"]}'
# 201 ETag: "1"  {"skuId":"LN-1","quantity":0,"details":{"name":"Linen shirt",...}}
curl -i -X PUT $API/v2/inventory/LN-1/details -H 'Content-Type: application/json' -H 'If-None-Match: *' \
     -d '{"name":"Other"}'
# 412 Details changed since you read them. Reload the SKU and retry with its new ETag.   (the SKU exists)
curl -i -X PUT $API/v2/inventory/LN-1/details -H 'Content-Type: application/json' -H 'If-Match: "1"' \
     -d '{"name":"Linen shirt, navy","images":[]}'
# 200 ETag: "2"  {"skuId":"LN-1","quantity":0,"details":{"name":"Linen shirt, navy","description":"","images":[]}}
curl -i -X PUT $API/v2/inventory/LN-1/details -H 'Content-Type: application/json' -H 'If-Match: "1"' \
     -d '{"name":"Stale edit"}'
# 412 (the tag is now "2")
curl -i $API/v2/inventory/ABC-1   # 200 ETag: "0"  {"skuId":"ABC-1","quantity":3}   (no details yet)
```

Field rules: `name` 1–120 characters, `description` up to 2,000, `cost.amount` an integer in minor units with a three-letter uppercase `cost.currency` (both or neither), up to 10 absolute http(s) `images` URLs of up to 2,048 characters. A SKU created by the details PUT can then be given stock with a keyed `POST /v2/inventory/LN-1`. An unconditional PUT to a mistyped id (`ABC-l` for `ABC-1`) creates a SKU at quantity 0 that is listed by both versions, and no operation deletes it (G5); send `If-None-Match: *` on a create, as the front end does, to refuse an id that already exists (it does not catch a typo that is a new id).

**Paging.** Both lists are sorted by SKU ID, at most 250 per response, with a `Link: <url>; rel="next"` header while more follow. `after` is the last SKU ID of the previous page:

```bash
curl -i "$API/v2/inventory?limit=2"
# 200 [{"skuId":"ABC-1","quantity":3},{"skuId":"K-1","quantity":3}]
# Link: <http://localhost:8080/v2/inventory?limit=2&after=K-1>   (with the default port); rel="next"
curl -i "$API/v2/inventory?limit=2&after=K-1"   # the next page (its items carry details when they have them)
curl -i "$API/inventory?limit=2"                # limit is ignored: every SKU up to 250; with more than 250,
                                                    # Link: <http://localhost:8080/inventory?after=...>; rel="next"
```

## How stock is stored

Short version of [`DESIGN.md`](DESIGN.md) (§3–§5):

- **Stock:** `sku.quantity` (`CHECK (quantity >= 0)`) and `sku.version`. A purchase is `UPDATE sku SET quantity = quantity - :q, … WHERE sku_id = :id AND quantity >= :q RETURNING …` in a READ COMMITTED transaction; no row updated means "insufficient" (or "not found"). Concurrent writers on one SKU queue on the row lock; writers on different SKUs never interact. Adds are guarded the same way against overflowing a bigint.
- **Ledger:** every change also inserts an `inventory_ledger` row in the same transaction; a trigger makes `UPDATE`/`DELETE` on the ledger (and `DELETE` on `sku`) fail. Every `@IntegrationTest` test asserts (`BalancesRecordedExtension`; a test can opt out only with `@AllowsBalanceMismatch(reason)`, none does today) `quantity = SUM(quantity_delta)` for every SKU after every test that writes.
- **Details:** `sku_details` is a separate row per SKU, never columns on `sku`, so the hot stock row stays narrow.
- **Idempotency (`/v2` only):** the claim (`INSERT … ON CONFLICT DO NOTHING`) and the stored response live in Postgres in the stock transaction, so a key changes stock at most once even for concurrent repeats. The request hash includes the API version.
- **Reads:** one primary-key read at READ COMMITTED, so a read is always the last committed count. There is no cache; Postgres is the only store.

**Failure behaviour.** Postgres down: reads and writes answer 500 `Internal server error`, nothing is acknowledged, and `/actuator/health/readiness` reports DOWN. A crash inside a transaction rolls it back (no stock change, no ledger row, no claim); a `/v2` retry with the same key claims afresh. `/actuator/health` names its component (`db`) without details.

**Operations.** `idempotency_keys` rows are kept (a key older than 24 h must keep being rejected). To reclaim space, clear the stored `status`, `content_type` and `body` of rows older than 24 h (`UPDATE idempotency_keys SET status = NULL, content_type = NULL, body = NULL WHERE created_at < now() - interval '24 hours'`); a cleared row still rejects reuse with 400, whatever its age. The table has no index on `created_at`, so run that statement off-peak or add the index first; the freed space returns after VACUUM. Growth: one `inventory_ledger` row (a few dozen bytes: an estimate, not measured) per successful add or purchase, kept forever (the trigger forbids deletion; archive by partitioning if a year of traffic matters), and one `idempotency_keys` row (a few hundred bytes with its body: an estimate, not measured) per keyed `/v2` request that passes validation, including 404s, since there is no authentication. Only the versioned writes touch that table.

## Assumptions

The spec leaves these open. The decision IDs in parentheses are cards in [`DECISIONS.md`](DECISIONS.md) (OD-n are the owner's decisions in [`ai/final/PROMPT.md`](ai/final/PROMPT.md)).

- **SKU IDs** are case-sensitive, 1–64 characters, `[A-Za-z0-9][A-Za-z0-9._-]*` (G1, G11). An invalid ID is 404 on reads and purchases, 400 where it would be created (unversioned add, `/v2` add, details PUT). An encoded slash is part of the ID (`/inventory/A%2FB` → 404) (C1).
- **`;` in the SKU segment.** Spring drops `;` content from a path variable, which would send `/inventory/ABC-1;lot=7` to `ABC-1`; a request guard treats the segment as sent (`ABC-1;lot=7`, also as `%3B`), so it is an invalid ID and nothing is written. `;` on the literal segments (`/inventory;v=1/…`) is ignored, as Spring does (C3). The guard applies to every write route of both versions.
- **Quantities** are 64-bit in storage and responses; a request adds or purchases 1 to 2,147,483,647. An add that would overflow returns 400 and changes nothing (G2, G12, V2).
- **Strict JSON, 400 and not 415.** Malformed JSON (including trailing tokens), a missing body, a wrong or missing Content-Type, a non-integer or null `quantity` and any other client error on the POSTs and the details PUT answer 400 `Invalid request`; unknown fields are ignored (G3, G13). A failed precondition on the details PUT is 412.
- **Body caps.** A POST body over 4 KB is 400 (A19); a details PUT body over 64 KB is 400. Both versions count a chunked body while it is read, so the cap holds without a `Content-Length` (the critique found that the unversioned POSTs checked only `Content-Length`, so a chunked body of any size was accepted; the service fix closes it).
- **Accept.** GET ignores Accept. A POST whose Accept excludes JSON, or gives it q=0 (most specific matching range decides), is 400 (U2, Y1, C3).
- **Undecodable query string** (`?x=%zz`) is 400 on every path that reads its query (`GET /inventory`, `GET /v2/inventory`, `/actuator/**`, the Swagger UI); the item routes, the POSTs, unknown paths and `/v3/api-docs` never read it and answer as usual. The spec lists no 400 for `GET /inventory`; this one is a recorded deviation of hardening (Z3). A repeated `after` is 400; a bad `limit` (non-numeric or below 1) is ignored and above 250 means 250 (Z3, R4, C2).
- **Error texts.** Exactly `SKU not found`, `Insufficient inventory`, `Invalid request`, `Internal server error` (G6) and the 412 text above; every other status uses its standard reason phrase (`Method Not Allowed`, …). Errors are `text/plain`, including Tomcat-level rejections (bad percent-escapes, oversized headers) and TRACE (C1, S5).
- **Path rejected by Tomcat.** A path Tomcat refuses before routing (`%00`, `%5C`, a bad percent-escape) answers 400 `Invalid request` on every method, including `GET /inventory/{skuId}`, where every other invalid ID is 404 (C1); the spec lists no 400 for that GET.
- **Integer-valued floats** such as `5.0` or `1e2` are refused as a `quantity`: the JSON is deliberately strict, and an integer is written without a fraction or exponent (G13).
- **Error media type.** Every error is `text/plain`; those written by the exception advice have no charset parameter and those written by the Accept interceptor, the guard filter and Tomcat's error valve are `text/plain;charset=UTF-8`. Same media type; compare by media type, not by string.
- **A SKU sold to 0** keeps its row and stays listed (G5).
- **`Idempotency-Key`** (`/v2`) is a UUID; a repeated request replays the first response, including 404 and 400 outcomes (a stored 200 keeps the details the first request saw); a different SKU, operation or quantity, a key older than 24 h, and a claimed-but-incomplete key are 400. The request hash is computed from the parsed request (whitespace and unknown fields don't matter) and includes the API version (Y3, H10).
- **The list `Link`** is built from the request's scheme and `Host` (no `X-Forwarded-*` handling) and the routed path (C2). It is built from the request's Host: behind a proxy, set the Host.
- **No authentication** (G10). Requests outside the API's operations get standard codes with the reason phrase as text; `/actuator/**` and the springdoc paths keep their own responses, except Tomcat-level rejections and undecodable queries, which are text/plain everywhere (S6, T3, C1).
- **Deployment.** The app is published on 0.0.0.0:8080 (all interfaces) without authentication so reviewers can reach it; Postgres is on loopback. springdoc is on by default (`/v3/api-docs`, `/swagger-ui.html`); set `springdoc.api-docs.enabled=false` (environment `SPRINGDOC_API_DOCS_ENABLED=false`) to turn the documents off. `-XX:MaxRAMPercentage=75.0` is relative to the container's memory limit, so set one in a real deployment.
- **Timeouts.** A request waits at most about 3 s for a pooled database connection (`spring.datasource.hikari.connection-timeout: 3000`) and about 5 s for a row lock (`SET lock_timeout = 5000` in `connection-init-sql`), then answers 500 `Internal server error` and writes nothing; with several waits in one burst a write can take up to about 10 s. Before the critique fix the Hikari default of 30 s applied, and a stalled row lock or a stopped database could hold every request for 30 s (and starve liveness at 200 busy threads). The init SQL also applies to Flyway's connection, so a migration that waits more than 5 s for a lock fails startup. The app service has `mem_limit: 768m` in `compose.override.yaml` (the JVM's `MaxRAMPercentage=75` follows it; a plain `docker run` has no cap).
- **No performance claim.** No benchmark has been run; nothing here says how fast the service is.

## Deviations

From the spec, on the unversioned paths (recorded in [`DECISIONS.md`](DECISIONS.md) as H3 and H4):

- **OD-5: the list is paged.** `GET /inventory` returns at most 250 SKUs and takes an `after` cursor with a `Link: rel="next"` header; `limit` and other parameters are ignored. The spec's list is unbounded, which failed with an OutOfMemoryError at 1M SKUs in build v1's critique (C-02). `openapi.yaml` documents the cap, the cursor, the `Link` header and a 400 (undecodable or repeated `after`).
- **OD-4: any `Idempotency-Key` is 400.** The spec has no such header; the unversioned POSTs refuse it rather than pretend to be retry-safe.

From the build process ([`ai/final/deviations.md`](ai/final/deviations.md)):

- **D-1: the CI workflow is not active.** GitHub rejected the push of `.github/workflows/ci.yml` because the token lacks the `workflow` scope. The workflow is parked at `ai/final/ci-workflow.yml` and has never run on GitHub, so it is untested there; the local `scripts/gate.sh --e2e` was the merge gate for every PR instead. **Owner action:** `gh auth refresh -h github.com -s workflow`, then `git mv ai/final/ci-workflow.yml .github/workflows/ci.yml` and push (issue #92 stays open until a run is green).
- **D-2: one force-push of a pull-request branch.** The prompt says never to force-push; `final-invariants` (PR #114) was rebased after its first push and pushed with `--force` (dd5bcb8 to c3c0e17). `final` and every other branch were untouched. Recorded in [`ai/final/deviations.md`](ai/final/deviations.md).

Known divergences kept frozen from build v2 (recorded, not fixed; changing one is the owner's decision). The critique also found that the unversioned POSTs capped only `Content-Length`, so a chunked body was unbounded; the service fix counts 4096 bytes while reading on both POSTs of both versions, so that is no longer a divergence:

- **Build v2's M-13:** the request guard runs before `@Valid`, so a `;` SKU with a bad body on purchase answers 404, not 400.
- **Accept tie-break:** among equally specific Accept ranges the first listed decides (main's C3 amendment says the highest q).

## Designed, not built

Deferred, with their reasons and decision IDs; none changes the API above.

- **Store a domain outcome instead of the HTTP response against an Idempotency-Key** (A35, [#83](https://github.com/kgtech/nuuly-take-home/issues/83), [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md) §11). Every keyed caller today is the HTTP controller, so it would change no behavior, and it would trade byte-for-byte replays (Y4) for re-rendered ones.
- **Messaging: an outbox to a broker for ERP sync, throughput and flash sales** (A36, [#85](https://github.com/kgtech/nuuly-take-home/issues/85), [`DESIGN-V2.md`](ai/v2/DESIGN-V2.md) §11). The spec's operations answer synchronously (G10); the no-oversell and exactly-once guarantees already hold in one Postgres transaction; no consumer exists in this scope; hot-SKU throughput has not been measured. The first step is a load test.
- **Redis, a cache or a replay copy** (A30, A31, A32). Postgres alone holds every guarantee; no benchmark showed a need.
- **Authentication** (G10).
- **`X-Forwarded-*` handling and servlet-path support** (scope). The `Link` is built from `Host`; the app runs at the root context.
- **Typed `WriteResult<O>` and one `Page<T>`** (A38). The code keeps the sealed, non-generic `WriteResult` and two page records; the card follows the code ([`ai/final/board-followups.md`](ai/final/board-followups.md), item 11).
- **Any performance claim** waits for a benchmark recorded here.

## AI process

The decision board for this build is a private artifact, https://claude.ai/artifact/5SCRVQ6fveSeN3TfbpQDAG (main's board is separate and read only); [`DECISIONS.md`](DECISIONS.md) and [`CLAUDE.md`](CLAUDE.md) are generated from it by `ai/export-board.mjs`, and its database is dumped in [`ai/final/board-db/`](ai/final/board-db/) so the exports can be reproduced without the artifact. The final build was made in one autonomous run of the Fable model after a plan gate. Its records: the instructions [`ai/final/PROMPT.md`](ai/final/PROMPT.md), [`lessons.md`](ai/final/lessons.md) (what both earlier builds learned, with the test or rule that enforces each), [`plan.md`](ai/final/plan.md), [`log.md`](ai/final/log.md), [`current-state.md`](ai/final/current-state.md), [`board-cards.md`](ai/final/board-cards.md), [`issues.md`](ai/final/issues.md), [`preflight.md`](ai/final/preflight.md), [`deviations.md`](ai/final/deviations.md), and the decision board's URL above. [`ai/final/report.md`](ai/final/report.md) is the final report of the run; [`ai/final/critique.md`](ai/final/critique.md) the self-critique with the outcome of every finding; [`ai/final/interview-defense.md`](ai/final/interview-defense.md) the questions an interviewer would ask, with answers and the three weakest points. Build v2's history is in [`ai/v2/`](ai/v2/) ([`DESIGN-V2.md`](ai/v2/DESIGN-V2.md), [`DECISIONS-v2.md`](ai/v2/DECISIONS-v2.md), [`REPORT.md`](ai/v2/REPORT.md), [`run-records/`](ai/v2/run-records/)); build v1 is on `main`. The per-session prompt log is [`agent-prompts.md`](agent-prompts.md).
