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
| `inventory_ledger` | one row per stock change: `sku_id`, `quantity_delta <> 0`, `reason` add or purchase, `created_at` | Written only by Postgres: V5's trigger inserts the row whenever `sku.quantity` changes, and a guard refuses any other insert (A14). Append-only, enforced by a trigger that raises `P0001` on UPDATE and DELETE (A11); TRUNCATE is allowed for tests. These triggers protect against the API, not against the database owner: the app connects as the owner, which can disable a trigger, TRUNCATE or UPDATE `sku` directly (since V5 such an update is recorded; privilege separation was weighed and rejected, E3). See "Limits of the ledger trigger" below. |
| `sku_details` | `sku_id` primary key (references `sku`), name, description, cost, image URLs, `version` | Details are never columns on `sku` (A22): the stock UPDATE rewrites its row under a lock, so a wide row would tax every purchase. Rows are never deleted. |
| `idempotency_keys` | `idempotency_key uuid` primary key, `operation`, `sku_id`, `request_hash bytea(32)`, `status`, `content_type`, `body`, `created_at` | Only `/v2` writes touch it (invariant 5). All-NULL or all-set response columns by CHECK (Y4). Rows are never purged (R9); validity is 24 h by the database clock (T1). |

The recorded invariant (3): for every SKU, `sku.quantity` equals the sum of its ledger deltas. Postgres keeps it (V5, A14). `record_balance_change()`, run by an AFTER INSERT and an AFTER UPDATE trigger on `sku`, inserts the ledger row in the same statement whenever `quantity` changes (reason `add` for a positive delta, `purchase` for a negative one), and `ledger_only_from_balance()` refuses every other ledger insert with `P0001`. The conditional UPDATE still decides and returns the balance (E1); `StockRepository` no longer inserts ledger rows. `BalancesRecordedExtension` still asserts the invariant after every `@IntegrationTest` test (an explicit `@AllowsBalanceMismatch(reason)` is the only opt-out; none is used).

Migrations V1–V3 are published on `v2` and never edited. `V4__idempotency_checks.sql` (H9, approved) narrows the idempotency CHECKs that V3 widened for build v2's create route: `operation IN ('add','purchase')` and `status IN (200,400,404)`, both `NOT VALID`. `NOT VALID` enforces them for new rows without scanning old ones, so a database that still holds a `create`, 201 or 409 row migrates and keeps reading it (rows are never purged); such a key fails as a different operation (400). `V5__ledger_follows_balance.sql` (A14) adds the two ledger triggers. It first checks that every SKU's balance equals its ledger sum and fails the migration if one differs, so it never carries existing drift forward: reconcile first, then migrate.

**Limits of the ledger trigger.** Each was reproduced against PostgreSQL 18.6.

- **The owner can bypass it.** It holds against the API and against any role that doesn't own the tables. The table owner or a superuser can still break the invariant: `ALTER TABLE … DISABLE TRIGGER`, `TRUNCATE inventory_ledger` on its own (row triggers don't fire on TRUNCATE), `SET session_replication_role = replica` (superuser only), or a trigger of its own that inserts ledger rows (the guard only checks the trigger depth). The app connects as the owner, and compose's `inventory` user is a superuser, so the app's own credentials can do all four. The fix is privilege separation, which E3 rejected. With it, `record_balance_change()` becomes `SECURITY DEFINER` and the app loses INSERT on the ledger; a probe showed stock writes still work that way and a direct insert gets "permission denied".
- **Restores and replication.** `pg_restore --disable-triggers` and a logical-replication subscriber (which applies rows as `replica`) don't fire the triggers, so restoring or replicating `sku` without `inventory_ledger` would break the invariant.
- **The reason comes from the sign.** A positive change is `add` and a negative one `purchase`. A new reason, such as a return, needs the trigger to be told, for example through `SET LOCAL` and `current_setting()`.
- **A hand-written change is recorded, not prevented.** An owner's `UPDATE sku SET quantity = …` keeps the books balanced, but it is still an unreviewed stock change, and the ledger shows it as an ordinary add or purchase.
- **Closed by V5 itself, and tested:** a session's temporary table named `inventory_ledger` would have captured the row (both functions pin `search_path = public, pg_temp`), and an `UPDATE OF quantity` trigger would have missed a change made by another BEFORE trigger (the UPDATE trigger has no column list).

## 4. Concurrency (reused from build v2 §2, still true)

**Concurrency design history.** The first design kept stock as the SUM of an append-only ledger and ran every write at SERIALIZABLE with retries (cards V1, W1, D4). It was built first and worked, but concurrent writes to different SKUs raised 40001 serialization failures (issue #26). It was replaced by a balance row per SKU plus an append-only ledger at READ COMMITTED (E1, below); the older cards say so on their own lines.

All stock writes run in one READ COMMITTED transaction:

- **Add:** `INSERT INTO sku … ON CONFLICT DO NOTHING`, then `UPDATE sku SET quantity = quantity + :q, version = version + 1 WHERE sku_id = :id AND quantity <= max − :q RETURNING …` (no row → overflow → 400). The V5 trigger writes the ledger row in the same statement (A14).
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

Kept as build v2 has them (known and recorded): the filter runs before `@Valid`, so a `;` skuId with a bad body on purchase answers 404 rather than 400 (build v2's open item M-13, card H16); for equally specific Accept ranges the first listed decides (C3's card changes to match). Changing one is a decision for the owner. A review of the build found that the unversioned POSTs capped only `Content-Length`, leaving a chunked body unbounded; that was a denial-of-service risk, so the cap now counts 4096 bytes while reading on both POSTs of both versions.

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
| Postgres writes the ledger row from the balance change (V5) | Owner decision after the build: a write path that skips the ledger is refused, and a hand-written balance change is recorded, instead of only being caught by tests (A14). |

Reused from build v1 and #87: the decision-board process, the text/plain error contract, the keyset paging design, the C1 Tomcat text errors, most hardening tests. Reused from build v2: storage, concurrency, idempotency claim, the front end, the run-records layout.

## 10. Designed, not built

Carried forward with their IDs (README repeats this list): storing a domain outcome instead of the HTTP response (A35, #83); a message broker or outbox (A36, #85); Redis, a cache or a replay copy (A30, A31, A32); authentication (G10); `X-Forwarded-*` and servlet-path support (Scope); read replicas (H18, §11); a write-off endpoint (§12). Nothing here is claimed as a performance property without a benchmark.

## 11. Read replicas and scale (H18, designed, not built)

The service runs on one Postgres primary. Card H18 records that, and the design for adding read replicas; nothing here is built. No load test shows reads outgrowing the primary, and the README makes no performance claim.

**Short answer.** Replicas scale reads, not writes. Every decision stays on the primary: the conditional UPDATE (E1), the ledger trigger (A14), the idempotency claim and replay (R2), and the `If-Match`/`If-None-Match` checks. Replicas serve only reads that decide nothing. So write correctness doesn't change; what changes is how fresh a read is.

**What stays on the primary.**

- Both versions' add and purchase, `PUT …/details`, everything in `IdempotencyStore`, and the purchase's 404-versus-400 `EXISTS` read, which runs inside the write's transaction.
- The rule: no write is decided from a replica read. Checking stock on a replica and then writing would bring back oversell; the conditional UPDATE on the primary is the check.

**What a replica shows.**

- Streaming (physical) replicas replay whole transactions. The balance, its ledger row and the idempotency row commit together, so a replica can lag the primary but never shows a balance without its ledger row. Triggers don't run on a standby and don't need to.
- Logical replication and change-data capture apply rows with triggers off. Publish `sku` and `inventory_ledger` together and keep A14's triggers at their default (origin only); enabling them on a subscriber would write duplicate ledger rows.
- Since V5, each balance change has exactly one ledger row in the same commit, so the ledger is a dependable change feed for a later outbox or ERP sync (A36, #85).

**Routing (option B).**

| Request | Goes to | Why |
|---|---|---|
| `POST` add and purchase (both versions), `PUT …/details` | Primary | Decides the outcome |
| Idempotency claim, replay and the purchase's `EXISTS` read | Primary | Part of the write's transaction |
| `GET /inventory/{skuId}`, `GET /v2/inventory/{skuId}` | Replica | Display only; a stale `ETag` makes a later `If-Match` fail with 412 on the primary, never a wrong write |
| `GET /inventory`, `GET /v2/inventory` | Replica | Keyset pages stay correct, but the pages of one walk can come from different moments |
| Balance-versus-ledger reconciliation | Replica | A full scan, kept off the primary |

In code: a second `DataSource` and `JdbcClient`, used by `StockRepository.find` and `page` and the `/v2` reads that join `sku_details`. Routing on a read-only transaction flag won't work here, because the reads run with no transaction at all (E1).

**How fresh a read is.** The README's "a read is always the last committed count" becomes "the last committed count, up to the lag limit". In layers:

1. **Lag limit.** A replica leaves the read pool when its replay lag passes the limit, read on the primary from `pg_stat_replication.replay_lag` (`now() - pg_last_xact_replay_timestamp()` on the replica also grows while the primary is idle, so it overstates lag). With no replica left, reads go to the primary.
2. **The write's own response.** Every write already returns the new quantity (`InventoryItem`, `SkuItem`), so a client never needs to re-read its own write.
3. **Option C, only for a `/v2` client that must read its own writes.** A write returns its commit LSN in a response header; a read that sends it back is served by a replica whose `pg_last_wal_replay_lsn()` has reached it, otherwise by the primary. The unversioned API can't carry the header (H1).
4. **Option D, `synchronous_commit = remote_apply`**, makes the listed replicas current before each write is acknowledged. Every commit then waits for the slowest of them, so it isn't recommended beyond one or two.

**Failover.** This is separate from read routing. With asynchronous replicas, promoting one after the primary fails can lose writes that were already acknowledged. They are lost consistently, because the balance, ledger row and key row are one transaction, so a retry with the same key applies once on the new primary; but the client was told 200 for a write that no longer exists. To keep every acknowledged write, commit synchronously to at least one standby (`synchronous_standby_names = 'ANY 1 (…)'`), at the cost of a network round trip per commit.

**Beyond replicas.**

- **Write capacity.** Writers to one SKU queue on its row lock. A local pgbench run (SQL only, 8 clients) gave about 1,100–1,600 writes per second on one SKU; that is a laptop figure, not a capacity claim. Past one primary, shard by `sku_id`: every write touches one SKU, so its row lock, trigger and ledger row stay on one shard and no transaction spans shards. The price is that key rows follow their SKU's shard, so a key reused on a SKU in another shard is no longer caught (S8).
- **Connection poolers.** PgBouncer in transaction mode drops per-connection settings, so `lock_timeout` (H17, set today by Hikari's init SQL) would move to `ALTER ROLE … SET lock_timeout = '5s'` or to `SET LOCAL` in each transaction.

**Checking the invariant in production.** Today `BalancesRecordedExtension` checks `quantity = SUM(quantity_delta)` in tests, and V5 enforces it against everyone but the table owner (§3). A scheduled reconciliation on a replica (the `Invariants.balanceMismatches` query) would catch an owner-level bypass and alert on any row.

**What building B takes** (H18 estimates about a day): the second `DataSource` and `JdbcClient`, the lag check, a Testcontainers primary with a streaming replica for the tests (a replica read may return the older quantity; a purchase decided while the replica lags never oversells; a replica past the limit is skipped), and the README's read promise changed through H18.

## 12. Write-off endpoint (designed, not built)

Today the only way to lower a balance is a purchase, so shrinkage (damaged, lost, expired, returned to the vendor) would be recorded as a sale. A write-off is a separate decrement with its own reason. No board card exists yet, so it has no decision ID; `DECISIONS.md` is generated from the board (S9), and a card must be added there before this is built.

**Shape.** `POST /v2/inventory/{skuId}/write-off`, body `{"quantity": n, "reason": "damaged" | "lost" | "expired" | "vendor_return"}`, `Idempotency-Key` required like the other `/v2` writes. It answers 200 with the remaining quantity, 404 "SKU not found", 400 "Insufficient inventory", or 400 "Invalid request". There is no unversioned route (the spec's four operations don't change, G10). `quantity` stays a positive Integer (V2); a negative `quantity` is never accepted, and the balance never goes below 0 (E1's CHECK).

**Concurrency (the same rule as a purchase, E1).** One statement decides: `UPDATE sku SET quantity = quantity - :q, version = version + 1 WHERE sku_id = :id AND quantity >= :q RETURNING quantity, version`, in the write's READ COMMITTED transaction, with the same 404-versus-400 `EXISTS` read. A write-off and a purchase of the same SKU queue on the row lock, and whichever the database runs second re-checks the committed balance, so together they can never take more than is on hand. It doesn't retry. A write-off racing a restock behaves like a purchase racing a restock, which `MixedStockConcurrencyTest` covers.

**The ledger change it needs.** V5's `record_balance_change()` derives the reason from the sign of the delta (`add` or `purchase`), and `inventory_ledger.reason` allows only those two values, so a write-off would be logged as a purchase. Building it means a migration that widens the CHECK to include `write_off` and its reason, and a way to pass the reason to the trigger without breaking A14's one-row-per-change rule: a transaction-local setting (`set_config('inventory.reason', …, true)`) that the trigger reads, defaulting to today's mapping when unset so existing writes are unchanged. The ledger would also need the sub-reason column (`damaged`, `lost`, …). A14's tests (`LedgerTriggerTest`, `Invariants`) extend to the new reason.

**Not the same as a stock-take.** "Set the count to n" is a different operation: an absolute write loses updates to a concurrent purchase, so it needs an `If-Match` on the SKU's version (the mechanism `PUT …/details` already uses) or must be expressed as a signed delta computed against a read the caller holds. It is out of scope here.

**Tests it would need.** Write-offs racing purchases of the same SKU (sold plus written off never exceeds stock, the leftover is smaller than every refused amount); a write-off racing a restock; the same key from many threads writes off once; the ledger row carries the `write_off` reason and the balance still equals the ledger sum.
