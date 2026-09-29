# DESIGN: the final Inventory API

This is the design of the `final` build. It was written at the plan gate, approved by the owner on 2026-09-29 (every recommendation, including the three that were marked pending: hash includes the version, V4 narrows the CHECKs, the details PUT creates unconditionally), and updated after the build to describe what was built; the README and `ai/final/report.md` record the run. Build v2's design (`ai/v2/DESIGN-V2.md`) stays as history. Where this file reuses build v2's text, it is because that text is still true; sections that changed say so.

Contents: 1 what final is · 2 versioning · 3 storage · 4 concurrency · 5 idempotency · 6 paging · 7 request hardening · 8 failure behavior · 9 what changed from build v2 and why · 10 designed, not built.

## 1. What final is

One service, one Postgres, two API versions over the same stock.

- **Unversioned (`/inventory…`)** is the take-home spec: four operations, the spec's status codes, `text/plain` errors. Two recorded differences from the spec: the list is capped at 250 with an `after` cursor (OD-5), and a request that carries `Idempotency-Key` is refused with 400 (OD-4). Hardening that only ever answers with a spec status code stays (skuId pattern, strict JSON, 400 not 415, POST Accept handling, `;` in the skuId segment, body cap, Tomcat-level text/plain errors, TRACE).
- **`/v2/inventory…`** holds everything the spec did not ask for: keyed writes, page-size control, SKU details, and later additions. Its routes mirror the spec's operations at the same relative paths; details are a sub-resource.

Postgres is the only data store. There is no cache, no queue and no second copy of anything (A30).

## 2. Versioning policy (OD-2)

- The unversioned API is frozen to the spec. An extension never lands there.
- `/v2` may grow compatibly (new operations, new optional fields, new optional headers). A change that would break a `/v2` caller gets a new prefix, `/v3`. Old prefixes are removed only by an owner decision.
- Both versions read and write the same rows. A SKU created through `/v2` is visible unversioned and the other way round; the version-parity invariant (listing and item reads return the same quantity, and paging both by their `Link`s visits the same pairs in the same order) is tested, because it is true by construction (one `sku.quantity` column) and would be easy to break by adding a second source.
- Why a path prefix and not a header: the version is visible in a URL, a log line, a curl example and the Swagger UI; it needs no default for callers that send nothing; caches and proxies key on the URL. The price is two route sets and two OpenAPI files. Providers differ (sources read 2026-09-29):
  - Google AIP-185 puts the major version "first part of REST API URI paths" and requires a new major version for incompatible changes (https://google.aip.dev/185). Shopify puts a quarterly version in the URL and supports each for at least 12 months (https://shopify.dev/docs/api/usage/versioning). This build matches both on mechanism.
  - GitHub sends `X-GitHub-Api-Version` (date-based; a default when absent; the previous version supported 24 months, https://docs.github.com/en/rest/about-the-rest-api/api-versions). Stripe sends `Stripe-Version` (monthly non-breaking releases, breaking majors twice a year, https://docs.stripe.com/api/versioning). This build differs from both on purpose: the spec's URLs must stay byte for byte, and a header default would make them a version too.
  - Unlike AIP-185, there is no `/v1` prefix: the unversioned paths are implicitly version 1 because the assessment fixes them.
- "Compatible" for `/v2` means additive only: new fields in a response, new optional inputs (fields, headers, query parameters), new operations. Anything else (removing or renaming a field, making an input required, changing a status code or the meaning of a value) is a new prefix.
- Deprecation: a version is removed only by an owner decision; there is no sunset schedule (GitHub 24 months and Shopify 12 months are the comparison).
- The `/v2` `ETag` is the details validator only. It does not change when the quantity changes, which deviates from RFC 9110 §8.8.1 (a strong validator covers the whole 200 representation) and §9.3.4 (a validator on a PUT response must reflect the saved representation). It is harmless while reads are `no-store` and a conditional GET is ignored; honouring `If-None-Match` on GET would need a separate details resource or `/v3`. The contract is the owner's (Target API in `ai/final/PROMPT.md`).
- Each version has its own OpenAPI document: `openapi.yaml` (1.0.0, checked against the spec by a conformance test) and `openapi-v2.yaml` (2.0.0). The front end is generated from the second only, so a call to an unversioned path fails the type check.

## 3. Storage (unchanged from build v2, §1–§2 of `ai/v2/DESIGN-V2.md`, minus Redis)

| Table | Holds | Notes |
|---|---|---|
| `sku` | `sku_id varchar(64) COLLATE "C"` primary key, `quantity bigint CHECK (quantity >= 0)`, `version bigint` | The source of truth for stock. `sku_id` is case-sensitive (G1) and validated against `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` before any I/O (G11). |
| `inventory_ledger` | one row per stock change: `sku_id`, `quantity_delta <> 0`, `reason` add or purchase, `created_at` | Append-only, enforced by a trigger that raises `P0001` on UPDATE and DELETE (A11); TRUNCATE is allowed for tests. Append-only by trigger protects against the API, not against the database owner: the app connects as the owner, which can disable the trigger, TRUNCATE or UPDATE `sku` directly (privilege separation was weighed and rejected, E3). |
| `sku_details` | `sku_id` primary key (references `sku`), name, description, cost, image URLs, `version` | Details are never columns on `sku` (A22): the stock UPDATE rewrites its row under a lock, so a wide row would tax every purchase. Rows are never deleted. |
| `idempotency_keys` | `idempotency_key uuid` primary key, `operation`, `sku_id`, `request_hash bytea(32)`, `status`, `content_type`, `body`, `created_at` | Only `/v2` writes touch it (invariant 5). All-NULL or all-set response columns by CHECK (Y4). Rows are never purged (R9); validity is 24 h by the database clock (T1). |

The recorded invariant (3): for every SKU, `sku.quantity` equals the sum of its ledger deltas. `BalancesRecordedExtension` asserts it after every `@IntegrationTest` test (an explicit `@AllowsBalanceMismatch(reason)` is the only opt-out; none is used).

Migrations V1–V3 are published on `v2` and never edited. `V4__idempotency_checks.sql` (H9, approved) narrows the idempotency CHECKs that V3 widened for build v2's create route: `operation IN ('add','purchase')` and `status IN (200,400,404)`, both `NOT VALID`. `NOT VALID` enforces them for new rows without scanning old ones, so a database that still holds a `create`, 201 or 409 row migrates and keeps reading it (rows are never purged); such a key fails as a different operation (400).

## 4. Concurrency (reused from build v2 §2, still true)

**Concurrency design history.** The first design kept stock as the SUM of an append-only ledger and ran every write at SERIALIZABLE with retries (cards V1, W1, D4). It was built first and worked, but concurrent writes to different SKUs raised 40001 serialization failures (issue #26). It was replaced by a balance row per SKU plus an append-only ledger at READ COMMITTED (E1, below); the older cards say so on their own lines.

All stock writes run in one READ COMMITTED transaction:

- **Add:** `INSERT INTO sku … ON CONFLICT DO NOTHING`, then `UPDATE sku SET quantity = quantity + :q, version = version + 1 WHERE sku_id = :id AND quantity <= max − :q RETURNING …` (no row → overflow → 400), then the ledger insert.
- **Purchase:** `UPDATE sku SET quantity = quantity − :q … WHERE sku_id = :id AND quantity >= :q RETURNING …`; no row → a second read decides 404 (missing) or 400 "Insufficient inventory".
- **Why READ COMMITTED is enough:** two purchases of 5 against stock 5 both pass the first read; the second UPDATE waits on the row lock, Postgres re-evaluates its `WHERE` against the committed row (PostgreSQL docs, Read Committed), finds 0 and updates nothing. No lost update; `CHECK (quantity >= 0)` would refuse it anyway. Lock order is the same everywhere (idempotency row, `sku` row, then a key-share on the same row for the ledger's foreign key), so there is no cycle.
- **Known and accepted:** the purchase's 404-versus-400 read uses a later snapshot than its UPDATE. If a create for a brand-new SKU commits between the two, the purchase answers "Insufficient inventory" for a SKU that did not exist when the UPDATE ran. Stock is never wrong. (Card E1 changes to say so.)
- **Both versions share these statements.** Purchases through `/inventory` and `/v2/inventory` race on the same row lock; a test drives both at once and asserts exactly as many succeed as the stock allows.
- **The details PUT and stock.** `PUT …/details` never updates `sku.quantity` or `sku.version`. When the SKU is absent it inserts the `sku` row at 0 and the details row in one transaction (`ON CONFLICT DO NOTHING` on the `sku` insert, so a concurrent add that already created it is not overwritten). Two concurrent PUT-creates give one 201; the other is a replace (200) or, with `If-None-Match: *`, a 412. An add racing a PUT-create lands either way: the add's `INSERT … ON CONFLICT DO NOTHING` blocks on the primary-key index until the PUT commits, then updates the row. Tested by `SkuDetailsPutConcurrencyTest` (concurrent creates, `If-None-Match: *`, `If-Match`, and a create racing an add and a purchase with ledger and balance equal afterwards).

## 5. Idempotency (`/v2` only)

- **Required key.** `POST /v2/inventory/{skuId}` (add) and `…/purchase` require an `Idempotency-Key` UUID (OD-3, OD-4). Order: body → key (missing or malformed is 400) → skuId → claim and write (A34).
- **Claim and response in the stock transaction.** In the same transaction as the stock change: `INSERT INTO idempotency_keys … ON CONFLICT DO NOTHING RETURNING`. A row back means the claim is ours: write the stock, then `UPDATE` the response columns, commit. No row means a claim exists: `SELECT` it. A concurrent claimer's insert blocks this one on the unique index until it commits or rolls back, so the select sees a completed row or, after a rollback, the next claim succeeds. Exactly-once therefore holds because the claim is unique-indexed and commits with the stock change; two concurrent repeats give one stock change and the same response.
- **Reuse.** The same key with the same request (same version, operation, skuId and quantity; the body is hashed from the parsed request, so whitespace and unknown fields don't matter) replays the stored status, Content-Type and body unchanged (Y4). A different request, or a key older than 24 h, or a claimed-but-incomplete row is 400 "Invalid request". This is a deliberate choice of 400 over 409 or 422, for one reason: the spec's writes have one client-error status, so every problem with the request (a key reused for another request, an expired key, a claim still in flight) is the same 400 "Invalid request". The cost is that a client cannot tell a mismatch from an expiry from a request still running.
  - The IETF draft (draft-ietf-httpapi-idempotency-key-header-07, 2025-10-15; not a standard, "Expired & archived", https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/) says a missing key on a required operation SHOULD get 400 (matches), the same key with a different payload SHOULD get 422, and a concurrent duplicate SHOULD get 409. This build differs on the last two: a concurrent duplicate blocks on the unique index and then replays, so the caller has no error to handle. The draft calls the key a Structured Field String; this build, like Stripe, takes a bare UUID.
  - Stripe (https://docs.stripe.com/api/idempotent_requests) errors on a reused key with different parameters, saves the response of the first request including errors, and lets keys be pruned after 24 hours, treating a reused pruned key as new.
  - Why an expired key is rejected and not treated as new: rows are never purged (R9), and a key that once meant one request must never mean another. After 24 hours a retry of a lost response therefore cannot succeed, but it can never write twice either.
- **Hash includes the API version** (H10, approved): `RequestHash.ofV2` hashes `"v2\n"` before the operation, skuId and quantity, and `ApiVersion` (`UNVERSIONED`, `V2`) selects it, so a `/v2` hash never equals build v2's unversioned one. A key stored by build v2's unversioned POST therefore can't replay an `InventoryItem` body on `/v2`; it gets 400.
- **What is stored.** The rendered HTTP response (A35 defers storing a domain outcome). The stored 200 body is the `SkuItem` the first request saw, details included; a later details change doesn't alter a replay.
- **Not retry-safe on purpose.** Unversioned POSTs are not retry-safe, and they refuse the key so that no caller thinks otherwise (OD-4, README states it).
- `PUT …/details` ignores the key: PUT is idempotent by method, and `If-Match` / `If-None-Match` make it safe under concurrency.

## 6. Paging

Keyset paging on `sku_id` (COLLATE "C"): `WHERE sku_id > :after ORDER BY sku_id LIMIT n + 1`; the extra row tells whether a `Link: rel="next"` exists. `after` is a plain string, never validated.

| | Unversioned `GET /inventory` | `GET /v2/inventory` |
|---|---|---|
| Page size | fixed 250 | `limit` 1–250, default 250, lenient (non-numeric or < 1 → 250, > 250 → 250) |
| Link | `/inventory?after=…` | `/v2/inventory?limit=…&after=…` |
| 400 | undecodable query, repeated `after` | same |

Links are built from the routed path, never from the raw request URI (C2, L19). No list holds more than 250 items (invariant 7); the reason for a cap on a spec path is C-02, an OutOfMemoryError with an unbounded list at 1M SKUs. One `Paging` helper builds both Links and checks the repeated `after`.

## 7. Request hardening (frozen from build v2, now applied by route kind)

A servlet filter (`InventoryRequestGuardFilter`) runs before argument resolution. `RoutedPath` parses the routed path once (decoded segments, context path removed, `;` content of the literal segments ignored) and classifies it as a `RouteKind`: LIST, ITEM, PURCHASE, DETAILS (`/v2` only) or OTHER. A small route table in the filter maps each kind and method to its rule: the answer to a `;` skuId, whether the route is a write, and its body cap. For every write route of either version it applies: `;` in the skuId segment (ITEM write → 400, PURCHASE → 404, GET → 404, PUT details → 400), refusal of an Accept that excludes JSON or gives it q=0, and the body cap (4 KB for both POSTs, 64 KB for PUT details; every POST and the PUT counts its body while it is read, so a chunked body is capped too). Build v2 keyed these rules on "is it `/v2`" and segment counts, which left `/v2/…/purchase` unguarded (lesson L21); final removes both conditions and `RouteGuardCoverageTest` enumerates the mapped `{skuId}` routes and fails until each has a kind and a rule. `RoutedPath` is the only main class besides the error advice's log lines that reads the raw request URI; an ArchUnit rule enforces it (L19).

Kept as build v2 has them (known and recorded): the filter runs before `@Valid`, so a `;` skuId with a bad body on purchase answers 404 rather than 400 (M-13); for equally specific Accept ranges the first listed decides (C3's card changes to match). Changing one is a decision for the owner. A review of the build found that the unversioned POSTs capped only `Content-Length`, leaving a chunked body unbounded; that was a denial-of-service risk, so the cap now counts 4096 bytes while reading on both POSTs of both versions.

Other rules are not versioned: strict JSON (G13), 400 for every client error on the POSTs and PUT (G3), Tomcat rejections and undecodable queries as `text/plain` (C1, Z3), TRACE answered like any unsupported method.

Errors are `text/plain` built by one helper (S5), with G6's fixed texts plus the 412 text on `PUT …/details`. Unknown paths and unsupported methods keep the standard 404/405 (G10).

## 8. Failure behavior

| Failure | Behavior |
|---|---|
| Postgres down | Every endpoint but the health check answers 500 "Internal server error"; no write is acknowledged; `/actuator/health` reports DOWN (readiness 503). |
| Crash inside a transaction | Rolled back: no stock change, no ledger row, no claim, no details. A retry with the same key claims afresh. |
| Crash after commit, before the response reaches the caller | The change is durable. A `/v2` retry with the same key replays the stored response. An unversioned retry would add again: not retry-safe, and documented. |
| Two identical `/v2` requests at once | One claims, one blocks on the unique index, then replays. One stock change. |
| Key reuse for another request, expired key, incomplete claimed row | 400 "Invalid request", nothing written. |
| Precondition failure on the details PUT | 412 with the fixed text; nothing written. |
| Overflow (`quantity > 2^63 − 1`) | 400 "Invalid request", no ledger row. |
| Pool exhaustion or a lock timeout | 500 "Internal server error", logged with a stack trace; the request wrote nothing. |

There is no Redis, so no cache failure modes (build v2 §4's Redis rows are gone).

## 9. What changed from build v2, and why

| Change | Why |
|---|---|
| Spec operations at `/inventory` are exactly the spec (plus OD-5 and the frozen hardening); keys and `limit` and details left them | The spec never had them (OD-2, OD-3, OD-5). A reviewer can diff `openapi.yaml` against the spec. |
| `/v2` mirrors the spec operations; details became `PUT /v2/inventory/{skuId}/details`; create-with-details (201/409) and `PUT /v2/inventory/{skuId}` are removed | One stock write per request, and details that are idempotent by method (OD-6, OD-11). Removes the 409 text and `Operation.CREATE`. |
| `/v2` keys are required; unversioned POSTs reject the key | A retry-safe endpoint should not depend on the caller remembering the header, and an unversioned endpoint must not pretend to be retry-safe (OD-4). |
| Details PUT creates a missing SKU, guarded by `If-None-Match: *` | The Create page can create idempotently and never overwrite an existing SKU (OD-11); a second route for creation is not needed. |
| Hash includes the API version; V4 narrows the CHECKs (`NOT VALID`) | So an old key can't replay the wrong representation; schema says what can be stored. |
| Guard filter driven by route kind | Lesson L21: build v2's filter skipped `/v2` purchase. |
| Two OpenAPI groups (springdoc), exported to `openapi.yaml` and `openapi-v2.yaml`, and a conformance test against the spec's YAML | Replaces build v2's byte comparison against its own earlier export, which proved nothing about the spec. |
| Decision board is the source of truth again (OD-9, OD-10); cards changed to match the code where #87 described code that was never built | `DECISIONS.md` and `CLAUDE.md` must describe what runs. |
| ArchUnit guards; one shared test annotation and container | Lessons L19, L27, L31: rules written as text were still broken. |
| Ports 8080 and 5173 | The owner asked to use the ports main uses (H13). |

Reused from build v1 and #87: the decision-board process, the text/plain error contract, the keyset paging design, the C1 Tomcat text errors, most hardening tests. Reused from build v2: storage, concurrency, idempotency claim, the front end, the run-records layout.

## 10. Designed, not built

Carried forward with their IDs (README repeats this list): storing a domain outcome instead of the HTTP response (A35, #83); a message broker or outbox (A36, #85); Redis, a cache or a replay copy (A30, A31, A32); authentication (G10); `X-Forwarded-*` and servlet-path support (Scope). Nothing here is claimed as a performance property without a benchmark.
