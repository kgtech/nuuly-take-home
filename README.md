# Nuuly Inventory API

A REST inventory service for the Nuuly Services assessment. It receives stock by SKU, processes purchases and lists inventory. It is built with Java 25, Spring Boot 4.1.x (built with 4.1.1), Spring Data JPA and PostgreSQL.

> Status: stories 1–2 (project setup, ledger schema, and SERIALIZABLE ledger writes with retries and service outcomes) are built, and `./gradlew test` works (Testcontainers starts Postgres; JDK 25 + Docker required). `./gradlew bootRun`, `docker compose up --build` and the API arrive in later stories (compose in story 5); those commands below are the planned setup (D8, D9).

## Build and run

**Reviewers (Docker only):**

```bash
docker compose up --build
# API:        http://localhost:8080/inventory
# Swagger UI: http://localhost:8080/swagger-ui.html
# Health:     http://localhost:8080/actuator/health
```

**Development (JDK 25 + Docker):**

```bash
./gradlew bootRun   # starts Postgres from compose.yaml automatically
./gradlew test      # Testcontainers starts a throwaway Postgres
```

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
- The list is sorted by SKU ID. Optional `limit` and `after` query parameters page through it; without them every SKU is returned. The next page's URL is in the `Link` header. (G9)
- There is no authentication. Each operation in the spec returns only the status codes the spec lists for it (500 only for unexpected server errors); requests outside those operations get standard HTTP codes. (G10)
- A retried request with the same key returns the first response, including 404 and 400 "Insufficient inventory". Requests rejected by validation are not remembered and can be retried. (R1)
- Two simultaneous requests with the same key produce one change; the second gets the first one's response. (R2)
- Requests outside the spec's operations get standard HTTP codes: unknown paths 404, wrong methods 405. GET ignores the Accept header; a POST whose Accept excludes JSON returns 400 (U2). (R3)
- Invalid paging values never cause an error: a bad `limit` is ignored, a `limit` above the maximum is reduced to it, and `after` alone returns every SKU after it. (R4)
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

- **Idempotency cache in front of Postgres (R2).** A Redis `SET key … NX EX 86400` could turn duplicate keys away before they reach the database. That would reduce load and latency. Postgres (unique index plus `ON CONFLICT DO NOTHING`) would still be the source of truth, because Redis's own docs discourage relying on simple `SET NX` locks for correctness.
- **Timed reservations (D4).** A cart or reservation service could hold stock for a few minutes and release it if the purchase doesn't complete, the way ticketing sites do.

## How AI was used

- [`DECISIONS.md`](DECISIONS.md): every design decision, my reasoning, the options I rejected, and whether I matched the AI's recommendation.
- [`CLAUDE.md`](CLAUDE.md): coding rules for the AI agent, each tied to a decision ID.
- [`agent-prompts.md`](agent-prompts.md): each prompt, a summary of the output, what I accepted or rejected, and my response.
- [`ai/decision-board.html`](ai/decision-board.html): the interactive board I used to make the decisions. Open it in a browser. It opens without my choices, which are recorded in DECISIONS.md.
- [`ai/decision-review.md`](ai/decision-review.md): inconsistencies found by a parallel AI review of the decisions, with how each was resolved.
- [`ai/github-issues.md`](ai/github-issues.md): the build broken into eight GitHub stories in build order, each tied to its decisions.
- [`ai/research-sources.md`](ai/research-sources.md): the sources behind each option, including unverified claims and the test that would prove each one.
