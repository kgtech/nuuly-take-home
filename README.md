# Nuuly Inventory API

A REST inventory service for the Nuuly Services assessment. It receives stock by SKU, processes purchases and lists inventory. It is built with Java 25, Spring Boot 4.1.x (built with 4.1.1), Spring Data JPA and PostgreSQL 18. Stock is an append-only ledger (balance = SUM of the deltas), written in SERIALIZABLE transactions with retries, so concurrent purchases never oversell.

## Prerequisites

**To run it (reviewers):**

- Docker with Compose v2 and BuildKit (the default builder in current Docker Desktop, OrbStack and Docker Engine).
- Port 8080 free on the host.
- `curl` and `uuidgen` for the walk-through below.

**To build and test it (developers):**

- JDK 25. The Gradle wrapper (Gradle 9.1+) downloads Gradle itself and finds the JDK 25 toolchain.
- Docker, for Testcontainers and for `bootRun`'s Postgres.

Versions: Java 25, Spring Boot 4.1.x (built with 4.1.1), springdoc-openapi 3.1.x (built with 3.1.1), Gradle 9.1+, PostgreSQL 18. Library versions are set only in `gradle/libs.versions.toml` and the Gradle version only in `gradle/wrapper/gradle-wrapper.properties`. (S10)

## Build and run

**Reviewers (Docker with Compose v2):**

```bash
docker compose up --build
# API:        http://localhost:8080/inventory
# Swagger UI: http://localhost:8080/swagger-ui.html
# Health:     http://localhost:8080/actuator/health
#             http://localhost:8080/actuator/health/liveness
#             http://localhost:8080/actuator/health/readiness
docker compose down -v   # stop and remove the database
```

This builds the app image, starts Postgres 18, waits for its health check and then starts the app on port 8080. Port 8080 must be free. Postgres is published on a random host port (`docker compose port postgres 5432`), so a local Postgres on 5432 doesn't conflict. There is no named volume: data survives a stop and restart (Ctrl-C, `docker compose stop`) and is removed by `docker compose down -v`.

To run it in the background and wait until the app is ready:

```bash
docker compose up --build -d
until curl -sf localhost:8080/actuator/health/readiness >/dev/null; do sleep 2; done
```

**Development (JDK 25 + Docker):**

```bash
./gradlew clean build --warning-mode=fail # compile with -Werror and run all tests (Testcontainers starts Postgres)
./gradlew bootRun                         # starts Postgres from compose.yaml, stops it on exit
docker compose up -d postgres             # Postgres alone, e.g. for a debugger-launched app
```

An app started outside `bootRun` (IDE run configuration, `java -jar`) doesn't get the Docker Compose support, so give it the database explicitly:

```bash
export SPRING_DATASOURCE_URL="jdbc:postgresql://localhost:$(docker compose port postgres 5432 | cut -d: -f2)/inventory"
export SPRING_DATASOURCE_USERNAME=inventory SPRING_DATASOURCE_PASSWORD=inventory
```

`compose.yaml` defines only Postgres; `compose.override.yaml` adds the app, and `docker compose` reads both by default. `bootRun` reads `compose.yaml` only, through Spring Boot's Docker Compose support, which is a development-only dependency and isn't in the jar. Don't run `docker compose up` and `bootRun` together: both want port 8080. (D8, S4)

## Try it

Start from an empty database (`docker compose up --build`, as above; if you started it before, run `docker compose down -v` first, since data survives a restart). Each command shows the expected status and body. Error bodies are `text/plain`; successful bodies are JSON.

**The four operations:**

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
curl -i -X POST localhost:8080/inventory/NOPE/purchase -H 'Content-Type: application/json' \
     -d '{"quantity":1}'                             # 404 SKU not found
curl -i -X POST localhost:8080/inventory/ABC-1 -H 'Content-Type: application/json' \
     -d '{"quantity":0}'                             # 400 Invalid request
```

A POST to an existing SKU adds to its stock. `quantity` must be a JSON integer from 1 to 2,147,483,647.

**Idempotency-Key.** Either POST accepts an optional `Idempotency-Key` UUID header. Repeating the same request with the same key within 24 hours returns the first response and changes stock only once. The same key with a different SKU, endpoint or quantity returns 400 `Invalid request`, and so does any request with a key older than 24 hours; keys are never reused, so send a new key for each new request:

```bash
KEY=$(uuidgen)
for q in 5 5 6; do
  curl -i -X POST localhost:8080/inventory/K-1 -H 'Content-Type: application/json' \
       -H "Idempotency-Key: $KEY" -d "{\"quantity\":$q}"
done
# 200 {"skuId":"K-1","quantity":5}
# 200 {"skuId":"K-1","quantity":5}   replayed; stock is still 5
# 400 Invalid request                 same key, different quantity
```

The controller passes the raw header and SKU ID to the service. An `@Idempotent` interceptor on the service's stock-write methods checks the key, then the SKU ID, and then claims the key, changes stock and stores the response in one SERIALIZABLE transaction, which is retried as a whole on a serialization failure. Without a key, the service runs the same stock write on its own. (Z1)

**Paging.** `GET /inventory` without parameters returns every SKU, sorted by SKU ID. Add `limit` (1–250) to get one page; when more SKUs follow, the response has a `Link` header with the next page's URL, and the last page has none. With ABC-1 and K-1 from above, add two more SKUs and page through all four:

```bash
for s in B-2 C-3; do
  curl -s -X POST localhost:8080/inventory/$s -H 'Content-Type: application/json' -d '{"quantity":1}'; echo
done
curl -i 'localhost:8080/inventory?limit=2'
# 200 [{"skuId":"ABC-1","quantity":3},{"skuId":"B-2","quantity":1}]
# Link: <http://localhost:8080/inventory?limit=2&after=B-2>; rel="next"
curl -i 'localhost:8080/inventory?limit=2&after=B-2'
# 200 [{"skuId":"C-3","quantity":1},{"skuId":"K-1","quantity":5}]   last page, no Link header
```

Every page is a plain JSON array of items. `after` is the last SKU ID of the previous page. SKUs created behind the cursor during a walk are not seen by that walk. A query string that can't be decoded (e.g. `after=%zz`) or that repeats `after` returns 400 `Invalid request`. (G9, R4, Z3)

## API docs

- Swagger UI: http://localhost:8080/swagger-ui.html (redirects to `/swagger-ui/index.html`).
- OpenAPI JSON: http://localhost:8080/v3/api-docs; YAML: http://localhost:8080/v3/api-docs.yaml.
- [`openapi.yaml`](openapi.yaml) at the repo root is the committed export. `ApiDocsTest` regenerates it on every test run and fails with "openapi.yaml regenerated; commit it" when the file changed, so the committed copy always matches the code. Keys are sorted so the export is byte-stable. (D7, S12)
- The committed file's `servers` URL, `http://localhost`, is a placeholder from the MockMvc export. Point tools at http://localhost:8080; the running app's `/v3/api-docs` reports the host and port it was requested on.

The docs are generated from the hand-written controllers (code-first, springdoc-openapi). They keep the original spec's title, version, operationIds and summaries, and list exactly the status codes the spec lists for each operation, with error responses as `text/plain`. The one addition is a 400 `Invalid request` on `GET /inventory` for an undecodable query or a repeated `after` (Z3). The export is OpenAPI 3.1, where the original spec is 3.0.3.

## Assumptions

The OpenAPI spec leaves these behaviours open. This implementation does the following:

- SKU IDs are case-sensitive: `ABC` and `abc` are different SKUs. (G1)
- SKU IDs are 1–64 characters, start with a letter or digit, and otherwise use letters, digits, `.`, `_` or `-`. Creating any other ID returns 400; reading or purchasing one returns 404. (G11)
- Stock levels are 64-bit integers. (G2)
- Adding stock that would take a SKU above 9,223,372,036,854,775,807 returns 400 and changes nothing. (G12)
- Malformed JSON, a missing body, or a wrong Content-Type return 400, not 415. (G3)
- `quantity` must be a JSON integer: `"10"`, `10.5` and `null` return 400. Unknown fields are ignored. (G13)
- A purchase with an invalid body returns 400 even when the SKU doesn't exist. (G4)
- A SKU sold down to 0 still exists: GET returns quantity 0 and it stays in the list. (G5)
- Error bodies are fixed strings: `SKU not found`, `Insufficient inventory`, `Invalid request`, and `Internal server error` for unexpected 500s. They never show stock counts. (G6)
- Concurrent purchases never oversell, however many app instances run. (G7)
- Both POST endpoints accept an optional `Idempotency-Key` header. Repeating a request with the same key returns the first response and doesn't change stock again. (G8)
- Reusing an `Idempotency-Key` with a different body, SKU or endpoint returns 400. Keys expire after 24 hours and can't be reused after that. (G14)
- The list is sorted by SKU ID. Optional `limit` and `after` query parameters page through it; without them every SKU is returned. `after` is exclusive: the page starts with the first SKU ID after it. The next page's absolute URL, built from the request, is in the `Link` header. (G9)
- There is no authentication. Each operation in the spec returns only the status codes the spec lists for it, plus a 400 on `GET /inventory` for an undecodable query or a repeated `after` (500 only for unexpected server errors); requests outside those operations get standard HTTP codes. (G10, Z3)
- A retried request with the same key returns the first response, including 404 and 400 "Insufficient inventory". Requests rejected by validation are not remembered and can be retried. (R1)
- Two simultaneous requests with the same key produce one change; the second gets the first one's response. (R2)
- Requests outside the spec's operations get standard HTTP codes: unknown paths 404, wrong methods 405. GET ignores the Accept header; a POST whose Accept excludes JSON returns 400 (U2). (R3)
- Invalid paging values don't cause an error: a bad or repeated `limit` is ignored, a `limit` above the maximum is reduced to it, and `after` alone returns every SKU after it. `GET /inventory` returns 400 `Invalid request` only when its query string can't be decoded or repeats `after`. (R4, Z3)
- `limit` accepts up to 250. (R8)
- `Idempotency-Key` must be a UUID. An empty or non-UUID key returns 400. (S3)
- Unexpected server errors return 500 with the text/plain body `Internal server error`. The contract rules apply to `/inventory` URLs; `/actuator/health` returns 503 when the database is down, and `/swagger-ui.html` redirects to the UI. (S6)
- An `Idempotency-Key` older than 24 hours can't be reused; sending it again returns 400. (T1)
- Requests outside the spec's operations return the standard HTTP reason phrase as text, e.g. `Method Not Allowed`. (T3)
- An add rejected for overflow is remembered like other results: retrying it with the same key returns the same 400. (U1)
- GET requests ignore the `Accept` header and always return JSON; a POST whose `Accept` header excludes JSON returns 400. (U2)
- A single request adds or purchases at most 2,147,483,647 units; stock levels are 64-bit. (V2)
- A stock change that keeps conflicting with concurrent changes is retried up to 10 times; if it still conflicts, the request returns 500 `Internal server error`. (W2)
- A retry with the same `Idempotency-Key` counts as the same request when the endpoint, SKU and quantity match; whitespace, field order and unknown fields don't matter. (Y3)

## Future improvements

These are out of scope for the 24-hour build.

- **Idempotency cache in front of Postgres (R2, T1).** A Redis `SET key … NX EX 86400` could turn duplicate and expired keys away before they reach the database. That would reduce load and latency. Postgres (the primary key on the key plus `ON CONFLICT DO NOTHING`) would still be the source of truth, because Redis's own docs discourage relying on simple `SET NX` locks for correctness.
- **Timed reservations (D4).** A cart or reservation service could hold stock for a few minutes and release it if the purchase doesn't complete, the way ticketing sites do.
- **Periodic balance snapshots for the ledger (V1).** Every balance is the SUM of a SKU's ledger rows, read through an index on `sku_id`. A periodic snapshot row per SKU (balance up to a ledger id) would let reads and write checks sum only the rows after it, so a long history stays cheap.

## Designed, not built (T6)

Nothing. Every decision in [`DECISIONS.md`](DECISIONS.md) is built, in the build order from [`ai/decision-review.md`](ai/decision-review.md). The eight stories in [`ai/github-issues.md`](ai/github-issues.md) list the decisions each one covers:

| Story | Decisions | Pull request |
|---|---|---|
| 1. Project setup and Flyway schema for the ledger | D1, D2, R6, S10, D5, D9, D10, G5, G11, V1, W2 | [#9](https://github.com/kgtech/nuuly-take-home/pull/9) |
| 2. SERIALIZABLE ledger add and purchase, with retries | V1, W1, W2, X1, Y2, D3, D4, S1, S7, G2, G7, G12, V2, U1, R1, S11 | [#10](https://github.com/kgtech/nuuly-take-home/pull/10) |
| 3. The four spec operations, text/plain errors and contract tests | G1, G3, G4, G5, G6, G10, G11, G13, R1, R3, R7, S2, S5, S6, S12, T3, U1, U2, U3, Y1, D6, D7 | [#11](https://github.com/kgtech/nuuly-take-home/pull/11) |
| 4. Concurrency tests | D9, S11, W2, G7 | [#12](https://github.com/kgtech/nuuly-take-home/pull/12) |
| 5. Docker Compose, health checks and a clean-clone run | D8, S4, D10, S6 | [#13](https://github.com/kgtech/nuuly-take-home/pull/13) |
| 6. Optional Idempotency-Key on both POSTs | G8, G14, R1, R2, R9, S3, S8, T1, U1, U3, W1, W2, X1, Y1, Y3, Y4, S11 (and Z1, Z2) | [#14](https://github.com/kgtech/nuuly-take-home/pull/14), review follow-ups [#16](https://github.com/kgtech/nuuly-take-home/pull/16) |
| 7. Opt-in keyset paging for `GET /inventory` | G9, R4, R8, S11 (and Z3) | [#18](https://github.com/kgtech/nuuly-take-home/pull/18) |
| 8. OpenAPI export, final README and agent-prompts.md | D7, S12, D0, S9, S10, T6, R2, T1, D4, V1 | [#19](https://github.com/kgtech/nuuly-take-home/pull/19) |

Z1 and Z2 were decided during story 6 and Z3 during story 7, and each was built in that story's pull request. R5 and T2 only settle the wording of other decisions (G4 and S2) and have nothing to build. The items under Future improvements were never part of the design.

**Considered, not adopted.** Two proposals came out of the review of story 3 (PR #11) and I rejected them; see [`agent-prompts.md`](agent-prompts.md), entry 22:

- **P1: handle HEAD like GET.** The filter that makes GET ignore the `Accept` header (U2) applies to GET only; HEAD keeps Spring's default behaviour.
- **P2: reject duplicate JSON keys** (Jackson's `STRICT_DUPLICATE_DETECTION`). A body with a repeated key is accepted and the last value wins.

## How AI was used

- [`DECISIONS.md`](DECISIONS.md): every design decision, my reasoning, the options I rejected, and whether I matched the AI's recommendation.
- [`CLAUDE.md`](CLAUDE.md): coding rules for the AI agent, each tied to a decision ID.
- [`agent-prompts.md`](agent-prompts.md): each prompt, a summary of the output, what I accepted or rejected, and my response.
- [`ai/Prompt Template.md`](ai/Prompt%20Template.md): the prompt I used to build each story with sequential subagents (an orchestrator that plans, then test author, implementer, reviewer and fixer runs).
- [`docs/NUULY-ASSESSMENT-README-JUL-2026.md`](docs/NUULY-ASSESSMENT-README-JUL-2026.md): the original assessment and OpenAPI spec that the AI worked from.
- [`ai/decision-board.html`](ai/decision-board.html): the interactive board I used to make the decisions. Open it in a browser. It opens without my choices, which are recorded in DECISIONS.md.
- [`ai/export-board.mjs`](ai/export-board.mjs): runs the board's own export functions headless to regenerate DECISIONS.md and CLAUDE.md from the board's saved choices.
- [`ai/decision-review.md`](ai/decision-review.md): inconsistencies found by a parallel AI review of the decisions, with how each was resolved.
- [`ai/github-issues.md`](ai/github-issues.md): the build broken into eight GitHub stories in build order, each tied to its decisions.
- [`ai/research-sources.md`](ai/research-sources.md): the sources behind each option, including unverified claims and the test that would prove each one.
