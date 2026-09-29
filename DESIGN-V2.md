# DESIGN-V2: stock in Postgres rows

(§1–§6 describe the design as first built, with a Redis count cache and replay copy; §9 records their removal. Where they disagree, §9 wins. §10 replaces the idempotency advice with an explicit call. §11 lists what is designed but deliberately not built.)

V2 keeps the API contract of `spec/` and `openapi.yaml` unchanged, including the optional `Idempotency-Key` header, the status codes and the text/plain errors. It replaces the storage design underneath: `main` derives every balance from a SERIALIZABLE `SUM` over an append-only ledger and retries serialization failures; V2 keeps a current balance per SKU as a row, updates it conditionally at READ COMMITTED, still appends every change to the ledger, and uses Redis for two things only: a cache of the most-read stock counts, and a cache of completed idempotent responses.

## 1. Where things live and what is the source of truth

| Concern | Postgres | Redis | Source of truth |
|---|---|---|---|
| Stock (the number) | `sku.quantity bigint NOT NULL CHECK (quantity >= 0)` and `sku.version bigint` (incremented on every change) | `stock:{skuId}` hash `{q, v}` with a TTL; only for SKUs that are read | **Postgres**. Redis holds a copy that is never written without a newer version and never trusted for a write. |
| Every change | `inventory_ledger` rows (append-only, enforced by a trigger) | – | **Postgres** |
| Idempotency | `idempotency_keys` rows: key, operation, skuId, request hash, stored status/content-type/body, `created_at` (24h validity by the DB clock, T1) | `idem:{key}` hash `{op, sku, hash, status, ct, body}` with a 24h TTL: a copy of the completed row | **Postgres**. The claim (`INSERT ... ON CONFLICT DO NOTHING`) is the exactly-once mechanism. Redis short-circuits replays and mismatches only when it has the key; a miss always falls through to Postgres. |
| List / pagination | keyset over `sku` (`sku_id`, `quantity`) | – | Postgres |

Redis is a cache in both roles. Everything in it can be lost at any moment without breaking an invariant (section 4).

## 2. Flows

**Add stock** (`POST /inventory/{skuId}`), one READ COMMITTED transaction:
1. `INSERT INTO sku (sku_id) VALUES (:id) ON CONFLICT DO NOTHING` (quantity and version default to 0).
2. `UPDATE sku SET quantity = quantity + :q, version = version + 1 WHERE sku_id = :id AND quantity <= 9223372036854775807 - :q RETURNING quantity, version`. No row → `Overflow` → 400 "Invalid request" (G12, U1). The row lock serializes writers on this SKU; writers on other SKUs never conflict.

**Why READ COMMITTED is enough.** Two purchases of 5 against stock 5 may both read 5 before either commits. The second `UPDATE` waits on the row lock; when the first commits, Postgres re-evaluates the second's `WHERE quantity >= :q` against the *new* row version (PostgreSQL docs §13.2.1, Read Committed), sees 0 and updates nothing → "Insufficient inventory". No lost update is possible, and `CHECK (quantity >= 0)` would refuse one anyway. Lock order is the same in every transaction (idempotency row, then the `sku` row, then a KEY SHARE on that same row for the ledger's foreign key), so no cycle and no deadlock; two concurrent creates of a new SKU serialize on the primary key of the `INSERT ... ON CONFLICT`.

**A known race in the purchase's error text.** The purchase's `UPDATE` and its follow-up `SELECT EXISTS` use separate snapshots. If a create for a brand-new SKU commits between them, the purchase answers 400 "Insufficient inventory" although the SKU did not exist when the `UPDATE` ran; with an Idempotency-Key that 400 is stored. Stock is never wrong. Accepted at this scope; the alternative (locking the `sku` row first) would add a round trip to every purchase of a missing SKU.
3. `INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (:id, :q, 'add')`.
4. Commit. After commit (transaction synchronization): `stock:{skuId}` is refreshed **only if it exists** and only if `version` is newer than the cached one (one Lua script, section 3). Redis failure here is logged and ignored.

**Purchase** (`POST /inventory/{skuId}/purchase`), one READ COMMITTED transaction:
1. `UPDATE sku SET quantity = quantity - :q, version = version + 1 WHERE sku_id = :id AND quantity >= :q RETURNING quantity, version`. A row → success. No row → `SELECT 1 FROM sku WHERE sku_id = :id`: missing → 404 "SKU not found", present → 400 "Insufficient inventory".
2. `INSERT INTO inventory_ledger (..., -:q, 'purchase')`.
3. Commit, then the same post-commit cache refresh as add.

**Repeated `Idempotency-Key`** (either POST). The service handles the key with an explicit call to `IdempotencyStore.run` (§10; the idempotent-receiver advice of Z1 was kept until then), in this order:
1. Body validation, then the key format (S3), then the skuId (G11), as U3 orders them (A34); a malformed key or skuId is answered before any I/O and never stored.
2. **Redis fast path:** `HGETALL idem:{key}`. Hit with the same operation, skuId and request hash → replay the stored response without touching Postgres. Hit with a different operation, skuId or hash → 400 "Invalid request" without touching Postgres. Miss, or Redis unavailable → step 3.
   The copy carries the row's claim time (`created`); its TTL is the time left until `created + 24h`, written atomically with the fields, and a lookup ignores an entry as old as the row's validity, so an expired key always falls through to Postgres, which rejects it (T1). A replay never extends the copy's life.
3. **Postgres claim** in the stock transaction: `INSERT INTO idempotency_keys ... ON CONFLICT (idempotency_key) DO NOTHING RETURNING`. Claimed → run the write → `UPDATE` the response columns in the same transaction (all-or-none CHECK, Y4) → commit → after commit, `HSET idem:{key} ...` with a 24h TTL. Not claimed → `SELECT` the row (READ COMMITTED: a concurrent claimer's insert blocks this one on the unique index until it commits or rolls back, so the select sees a completed row or, after a rollback, the retry of the claim succeeds): expired or mismatched → 400; else replay the stored response and back-fill Redis.
4. A key older than 24h is rejected with 400 (T1, unchanged). Rows stay; Redis entries expire on their own.

Exactly once holds because the Postgres claim is unique-indexed and in the same transaction as the stock change: two concurrent repeats can both miss Redis, but only one claim inserts; the other blocks, then replays.

## 3. The read path for the most-used SKUs

`GET /inventory/{skuId}`:
1. `SkuId.isValid` first (no I/O for a malformed id, and no transaction is opened before it; C-05).
2. `HGETALL stock:{skuId}`. Hit → return `q` (no Postgres, no waiting on any writer).
3. Miss → `SELECT quantity, version FROM sku WHERE sku_id = :id` (READ COMMITTED autocommit: never waits on a row lock, sees the last committed value). Missing → 404. Found → `stock:{skuId}` is written with the cache script and the TTL, then returned.

**Cache script** (`stock-set.lua`, one round trip, atomic): given `q`, `v`, `ttlMillis` and `onlyIfPresent`: if the key is absent and `onlyIfPresent`, do nothing; else if the key is absent or its `v` < the new `v`, `HSET q v`, and `PEXPIRE ttl` only when populating (a refresh keeps the remaining TTL); else leave it (a stale writer never overwrites a newer value). Reads call it with `onlyIfPresent=false`; the post-commit write hook calls it with `onlyIfPresent=true`.

**Staleness bound.** A cached count is refreshed after every committed change to that SKU while the entry exists, so in normal operation a read is stale for the milliseconds between the commit and the post-commit `HSET`. The **guaranteed bound is the TTL, `inventory.cache.stock-ttl` (default 5 s), plus the latency of one read miss**: if the post-commit refresh fails (Redis down or slow, or the service crashes right after commit), the stale entry expires within the TTL and the next read goes to Postgres; and a read miss that populates with a row it fetched just before a concurrent commit (whose refresh found no entry and skipped) caches that row for one TTL, so the bound is the TTL measured from the read, not from the commit. Versions make the refresh monotonic, so two writes whose refreshes arrive out of order can't leave the older count behind past the TTL either: the later version wins immediately. The TTL is set only when a read populates; a refresh keeps the remaining TTL, so an entry stays only while it keeps being read. The bound is tested by writing directly to Postgres (bypassing the hook) and asserting the read is correct after the TTL.

**Which SKUs are "most used".** V2 keeps no popularity structure. Precisely: an entry lives one TTL from the read that populated it; a hit does not renew it, a write refreshes its value but keeps the remaining TTL, and the next read after expiry misses once and re-populates. So the hot set is **the SKUs read within the last TTL**, and the hottest SKU costs Postgres one read per TTL (0.2 reads/s at the 5 s default) however hard it is read. This is deliberate: the TTL doubles as the staleness bound, and renewing it on hits would let a hot entry go stale for as long as it stayed hot. Redis also runs with `maxmemory 64mb` and `maxmemory-policy allkeys-lfu` (compose); that is a backstop shared with the 24 h replay copies, not the mechanism that picks hot SKUs: it only matters when the entries read within one TTL plus the replay copies exceed the memory limit. SKUs outside the cache are read from Postgres (one indexed row lookup) and are correct, only slower. Writes never populate the cache (`onlyIfPresent`), so a SKU nobody reads never occupies memory.

## 4. Failure modes

| Failure | Behaviour |
|---|---|
| Redis down or unreachable | Every endpoint keeps working. Reads miss and go to Postgres; writes commit in Postgres and skip the post-commit refresh; the idempotency fast path is skipped and the Postgres claim decides. Redis calls use a 250 ms command timeout; each failure is logged at WARN (rate-limited to one line per 10 s per role) and the request continues. No 5xx is caused by Redis. |
| Redis slow | Same as down after the timeout. Each Redis call waits at most the 250 ms command timeout: a read miss makes two calls (lookup, populate), an unkeyed POST one (refresh), a keyed POST three (replay lookup, refresh, replay copy), so a keyed POST can take up to 750 ms longer (measured 794 ms with Redis paused). A3 accepts this instead of a circuit breaker. |
| Redis flushed (`FLUSHALL`) or restarted without persistence | All entries are lost: reads repopulate from Postgres, replays fall through to the Postgres claim (which still returns the stored response), so exactly once and correctness hold. Nothing authoritative was in Redis. Redis runs with `appendonly no`: persistence is not needed and is deliberately off. |
| Postgres down | Reads of cached hot SKUs still succeed from Redis (until the TTL). Everything else returns 500 "Internal server error" (G6); no write is acknowledged. The health endpoint reports DOWN. |
| Service crash between the Postgres commit and the Redis refresh | Postgres has the change (durable, recorded). The cache entry for that SKU is stale until its TTL (the bound in section 3); the idempotency entry is absent, so a replay goes to Postgres. There is no second authoritative write, so there is no torn state: Postgres is the only write that must succeed, and it is one transaction. |
| Service crash inside the transaction | Postgres rolls back: no stock change, no ledger row, no claim. A client retry with the same key claims afresh. |

## 5. Alternatives considered

1. **Redis as the stock authority (DECRBY in Redis, write-behind to Postgres).** Fastest writes, but "durable once acknowledged" would depend on Redis persistence (AOF fsync every write) and a failed write-behind would silently diverge the ledger from the count; a Redis restart with a lost AOF tail oversells. Rejected: the invariants say Postgres must record every change and success must survive a Redis restart; putting the authority in Postgres makes those free.
2. **Keep `main`'s SUM-ledger and only add a Redis read cache.** Least change, but the SUM keeps growing with history and the SERIALIZABLE predicate keeps making unrelated SKUs conflict (C-03). Rejected: the balance row removes both without losing the ledger.
3. **Redis-side idempotency only (SET NX with the response, 24h TTL).** Simpler, but a lost key (flush, eviction, restart) would let a repeat change stock twice, breaking exactly once "after Redis expires, evicts, or loses the key". Rejected in favour of the Postgres claim with Redis as a replay cache.
4. **A popularity ZSET (ZINCRBY per read, cache the top N).** Explicit "most used", but it is a second write per read, a second thing to keep consistent, and it needs a job to trim. Rejected: cache-on-read with TTL and LFU eviction gives the same effect with no extra state.
5. **Pub/sub or keyspace invalidation instead of post-commit refresh.** Rejected: a refresh carrying the version is one round trip and gives a tighter bound than an invalidation followed by a miss.
6. **Postgres-only idempotency, Redis only for hot reads.** Simpler by one class, one script and one fault path, and the first build rejected "Redis in front" for that reason (Z1 option D). Kept the Redis copy anyway, for a stated reason: the brief asks the storage design to use Redis for idempotency as well as reads, and the copy answers the two replay cases (same request → stored response, different request → 400) without a Postgres round trip and without touching the claim row. The cost is the code just listed plus the coupling that keeps the copy inside the row's validity (`KEY_VALIDITY` shared by SQL and cache, a `created` field, an age check). No throughput figure is claimed; replays are the rare retry case, so this is a design-brief decision, not a performance one (A13).
7. **A trigger that maintains `sku.quantity` from ledger inserts** (one write path, `quantity = SUM(deltas)` enforced by Postgres). Rejected: the conditional `UPDATE ... RETURNING` is what gives the no-oversell decision and the new balance in one statement; a trigger would move that decision into PL/pgSQL, hide the version bump, and make the write path two statements that both touch the row. The equality is asserted by `Invariants.balanceMismatches` in the concurrency tests instead (A14).

## 6. Comparison with `main`

| | `main` | V2 |
|---|---|---|
| Balance | SUM of ledger deltas per request | one row, updated in place |
| Isolation | SERIALIZABLE + up to 10 retries on 40001/40P01 | READ COMMITTED, row lock; no retry logic |
| Cross-SKU interference | SSI false conflicts (C-03) | none (different rows) |
| Ledger | append-only by convention (C-10 open) | append-only by trigger |
| Reads | SUM in a read-only transaction | Redis hit, else one row lookup |
| Idempotency | Postgres claim + replay | Postgres claim + replay, Redis fast path |
| Moving parts | Postgres | Postgres + Redis, two cache scripts, post-commit hooks |

**Tuning values.** `inventory.cache.stock-ttl` 5 s: long enough that a read storm on one SKU costs Postgres 0.2 reads/s, short enough that a count can never be a purchase-page lie for longer than a page load; it is configurable. `spring.data.redis.timeout` 250 ms: an order of magnitude above a local Redis round trip and well under a client's patience, so a slow Redis degrades to Postgres quickly. Redis `maxmemory 64mb`: about a million stock entries or a hundred thousand replay copies; a backstop, not a sizing claim (A15).

**Clocks.** Postgres decides a key's expiry with its own `now()`; the Redis copy compares the row's `created_at` with the app clock. A skew of s seconds lets Redis replay or reject a key for s seconds around the 24 h boundary. Accepted at this scope (A16).

Better in V2: writes and reads touch one row instead of a growing SUM; no serialization retries to reason about; the append-only rule is enforced in the database; hot reads don't reach Postgres. Worse in V2: two stores, a cache-consistency argument (versions + TTL) that has to be tested, `sku.quantity` and the ledger are now two representations of the same fact (a trigger-free invariant `quantity = SUM(deltas)` is checked by a test, not by the database), and an operator has a second service to run. Complexity cost: about 4 more classes (`StockCache`, `ReplayCache`, the Lua script, the Redis configuration), one more container, and the fault tests.

**Performance.** V2 makes no speed claim about reads or writes against `main`; a plain Postgres read on `main` also never waits on a writer. The structural difference (one row vs a SUM over history) is stated as such, without numbers.

## 7. Superseded entries

Everything not listed here still applies. IDs from `DECISIONS.md` / `CLAUDE.md`; L-nn from `lessons.md`.

| ID | Why superseded |
|---|---|
| V1 | Stock is a `sku.quantity` row updated conditionally, not a SUM over the ledger; the ledger stays append-only and still records every change. |
| D3, S1 | No Spring Data JPA and no repository fragment: all SQL runs through `JdbcClient` in package-private repositories (simpler, and no connection is borrowed before validation, C-05). |
| D4, W1, W2, X1, Y2 | Writes run at READ COMMITTED with row locks; there is no serialization failure to retry, so `@Retryable`, `SerializationFailure` and the SERIALIZABLE templates go. Deadlocks can't occur (one `sku` row per transaction, claim row locked first). |
| G7 | Correctness is enforced by the conditional `UPDATE ... WHERE quantity >= :q` and `CHECK (quantity >= 0)`, not by SERIALIZABLE plus retries. |
| G12 | Overflow is rejected by the `UPDATE ... WHERE quantity <= max - :q`; same 400 "Invalid request". |
| D5 (migrations content), D1/D9 (JPA parts) | Migrations declare `sku.quantity`, `sku.version`, the append-only trigger and Redis is not in Postgres; `spring.jpa.*` settings and the JPA starter are gone. Flyway and `validate`-equivalent checks stay (a test asserts the schema). |
| Z1 | First kept in structure (advice, REQUIRES_NEW) at READ COMMITTED, without the "not SERIALIZABLE → IllegalStateException" guard; then replaced by an explicit call to `IdempotencyStore.run` with `PROPAGATION_REQUIRED` (§10, A33). |
| U3, S2, S3 | The key format and the skuId are checked once each, in the service, in that order, before the claim (§10, A34); the advice's copy of the skuId check is gone. |
| R2 (40001 clause) | A concurrent claim blocks on the unique index instead of raising 40001; after the first commits, the second replays. |
| D8, S4 (compose content) | compose.yaml runs Postgres **and Redis**; the app depends on both. Reverted by §9: compose runs Postgres alone, as on `main`. |
| D10 | Package layout gains `cache/` (Redis) beside `inventory/` and `idempotency/`. Reverted by §9: `inventory/` and `idempotency/` only. Then `web/` gains the app-wide HTTP classes (error advice, `TextErrors`, Tomcat valve and container settings, Jackson and OpenAPI configuration), moved out of `inventory/web/`, which keeps the feature's package-private endpoints, filters and error answers (A37); ArchUnit guards the layout (A39). |
| Issues #24 AC3, #26 AC1–3, #29, #30 (test-suite items written against `main`'s tests) | Their intent is met by construction in V2 (one shared container per store, no SUM query plan to EXPLAIN, no SSI conflicts); the V2 issues restate them for V2's tests. |
| L-26 (retry lesson content), L-33 (cross-SKU SSI) | No longer apply without SERIALIZABLE; the cross-SKU concurrency test is kept as a regression guard. |

## 8. The v2 API: a SKU with details

The four spec operations stay exactly as they are (byte for byte in `openapi.yaml`, unchanged behaviour). v2 is additive: three paths under `/v2/inventory` give a SKU a name, a description, a cost and image URLs, and let a client create a SKU with those details and optional initial stock in one atomic call. Stock still changes only through the two spec POSTs. Every invariant above holds: the details live in their own table, the balance row and the ledger are untouched, initial stock goes through the same stock path (`StockRepository.add`) and writes the same `'add'` ledger row, and the create honours `Idempotency-Key` with the whole request in the fingerprint.

| Operation | Does | Success | Errors (all text/plain) |
|---|---|---|---|
| `POST /v2/inventory/{skuId}` | Creates the SKU with `details` and `initialQuantity` (default 0) in one READ COMMITTED transaction | 201, `SkuItem`, `ETag` | 400 "Invalid request", 409 "SKU already exists…" |
| `PUT /v2/inventory/{skuId}` | Replaces the details (creates them for a SKU that has none); `If-Match` optional | 200, `SkuItem`, `ETag` | 400, 404 "SKU not found", 412 "Details changed…" |
| `GET /v2/inventory/{skuId}` | The SKU with its quantity and details (absent when it has none) | 200, `SkuItem`, `ETag` | 404 |
| `GET /v2/inventory` | One page, same `limit`/`after`/`Link` rules as G9 | 200, `SkuItem[]`, `Link` | 400 |

**Schema (V3).** `sku_details (sku_id PK → sku, name text, description text, cost_amount bigint, cost_currency char(3), images text[], version bigint, updated_at)`, with CHECKs for the field rules below, `(cost_amount IS NULL) = (cost_currency IS NULL)`, at most 10 images, and the same never-deleted trigger as `sku`. A separate table, not columns on `sku`: the stock `UPDATE` rewrites the whole `sku` row under its lock, and a wide row with a 2,000-character description would make every purchase copy text it never reads; a join on the primary key costs one index probe. `idempotency_keys` gains `'create'` as an operation and 201/409 as storable statuses.

**Representation.** `SkuItem = {skuId, quantity, details?: SkuDetails}`, `SkuDetails = {name, description, cost?: {amount, currency}, images: [url]}`; an optional object is omitted rather than sent as `null`, so the generated client types carry the absence as an optional property and no client has to tell `null` from missing. The `details` object is exactly the PUT body, so a client edits what it read. A SKU created by the spec's add stock has no details row and has no `details` property (the alternative, 404 from v2 for such a SKU, was rejected: the SKU exists, and the v2 list must show every SKU the v1 list shows). Cost is `amount` in minor units as an integer (int64, ≥ 0) plus a three-letter uppercase `currency` code, both present or the whole `cost` absent. A decimal string was rejected because two clients would round it differently, a float because it cannot hold 0.1; int64 rather than int32 so a cost is never capped below what an inventory system stores, at the accepted price that a JavaScript client cannot hold an amount above 2^53−1 exactly (the front end refuses to send or edit one, FE39, FE45); the code is matched against `^[A-Z]{3}$` and not against the ISO 4217 list (no dependency; a wrong code is a data error, not a contract one). Field rules: `name` 1–120 characters and not blank; `description` up to 2,000 characters, default ""; `images` up to 10 absolute `http`/`https` URLs of up to 2,048 ASCII characters (percent-encoded, so the byte cap below can be sized from the field limits), default []; `initialQuantity` 0 to 2,147,483,647 (the request int32 of V2), absent or `null` meaning 0. Text fields hold no C0 or C1 control characters (a description may hold newlines and tabs) and a JSON number or boolean is never stringified into them (G13 extended to strings) and no unpaired surrogate, since Postgres text cannot store them and a 500 is not a client error; lengths count UTF-16 units, as a JavaScript string does. The length, blank, cost and image-count rules are also CHECK constraints in V3, so no path around the validator can store a value they forbid; the control-character and coercion rules live in the record and Jackson only.

**Create.** One transaction, in lock order idempotency row → `sku` row → `sku_details` row → ledger: (1) `INSERT INTO sku (sku_id) VALUES (:id) ON CONFLICT DO NOTHING RETURNING sku_id`; no row → the SKU exists → 409, nothing written. (2) `INSERT INTO sku_details …`. (3) if `initialQuantity > 0`, `StockRepository.add` (the spec's UPDATE and `'add'` ledger row; a new row at 0 cannot overflow). (4) Commit (nothing runs after commit since §9). Two concurrent creates of one id serialize on the primary key: the second blocks until the first commits, then its INSERT returns no row and it answers 409. A concurrent spec add on the same new id blocks the same way and then adds to the created SKU. **409 on an existing SKU, including one the spec's add created without details.** The alternative, upsert (200 and replace the details), was rejected: a create that silently overwrites another client's details is the lost update this section otherwise prevents, and a retried create without a key would add the initial stock twice. The 409 body says what to do instead: "SKU already exists. Set its details with PUT /v2/inventory/{skuId}; add stock with POST /inventory/{skuId}." It is a fixed text with the literal `{skuId}`, not the id interpolated, so no request data is ever reflected into an error body (G6's rule for every text). A create with `Idempotency-Key` stores 201 and 409 like the spec POSTs store 200/400/404 (R1); a replayed 201 carries `ETag: "1"` even if a later PUT moved the details on, since a replay is the first response byte for byte (Y4) and the tag belongs to that response; the fingerprint (Y3) hashes `create\n{skuId}\n{canonical request}` where the canonical form renders the parsed request field by field, each field as `length:value\n` (name, description, cost as `amount CCY` or `-`, the image count, each image, initialQuantity, with an absent initialQuantity as 0): length prefixes keep a value that contains a newline from colliding with a field boundary, and the raw body is never hashed.

**Edit: PUT, full replacement, `If-Match` optional.** PUT with the whole `SkuDetails`: what GET returns is what PUT accepts, so a client cannot drop a field by accident and an omitted `cost` means "no cost", not "leave it". JSON Merge Patch was rejected because an omitted field means "leave it" there and `null` means "remove", so clearing the cost and forgetting it are two different requests a client must not confuse; JSON Patch was rejected as a second grammar for four fields. Concurrency: every `SkuItem` carries a strong `ETag` (`"<details version>"`, `"0"` for a SKU without details); a PUT with `If-Match` succeeds only when the current version is one of the listed tags, else 412 "Details changed since you read them. Reload the SKU and retry with its new ETag." A PUT without `If-Match` (or with `*`) is unconditional: last write wins. Mandatory `If-Match` (428) was rejected: it turns every curl and script edit into two calls for a benefit only concurrent editors get, and the front end always sends the header. The upsert is one statement: `INSERT … ON CONFLICT (sku_id) DO UPDATE SET …, version = sku_details.version + 1 WHERE :expected IS NULL OR sku_details.version = ANY(:expected)`, gated by `EXISTS (SELECT 1 FROM sku …)` so a PUT never creates a SKU; no row back → the SKU is missing (404) or the version did not match (412), told apart by one `SELECT`. A PUT never updates `sku`: its only lock on that row is the foreign key's KEY SHARE (taken when a details row is first inserted), which is compatible with the stock write's FOR NO KEY UPDATE, so a PUT and a purchase never wait for each other (tested with a purchase holding the row lock). A PUT on a malformed skuId is 400 like the create, not 404 like GET and purchase: PUT is a write with a 400 in its contract, and G11's 404 rule exists only because GET has no 400 in the spec. An unconditional PUT that finds no SKU answers 404 without a second probe, so a create committing between two statements cannot turn it into a 412; for a conditional PUT the 404-or-412 probe runs on a later snapshot, the same accepted race as the purchase's error text in §2. `If-None-Match` is not honoured on the v2 reads, and not merely omitted: the ETag is the details version while the body carries a quantity that changes with every purchase, so a 304 keyed on it would let a browser keep a stale count. Spring answers a matching `If-None-Match` on a `ResponseEntity` with an ETag by itself, which the front end's reload after a purchase exposed (the page showed the pre-purchase count); the Accept filter therefore hides `If-None-Match` and `If-Modified-Since` from the v2 reads, and every v2 item and list response is `Cache-Control: no-store`. Deliberate omissions: and an empty `If-Match:` header is treated as malformed (400) rather than an empty list.

**Reads.** `GET /v2/inventory/{skuId}` is one query, `sku LEFT JOIN sku_details` on the primary key, at READ COMMITTED autocommit; it does not use the Redis stock cache and does not populate it. Caching the details was rejected: they change rarely and are read with the count, and a cached copy would need an invalidation on PUT plus a second staleness argument for a read that costs one index probe. Reading the count from the cache and the details from Postgres was rejected too: one response would mix two sources with different staleness. The spec's `GET /inventory/{skuId}` keeps the cached hot path; a client that only needs the count keeps using it. The v2 list is the same keyset query as G9 with the join, the same `limit`/`after` parsing and the same `Link`, built on `/v2/inventory`; it exists because a list that had to fetch details per row would be N+1 for the caller.

**Errors stay text/plain.** v2 answers errors exactly like v1: a fixed text per status through the one helper (S5, G6, T3), never JSON. RFC 9457 problem details were considered and rejected: one API would then have two error grammars, the front end two error paths, and the tests two matrices, for four situations a sentence describes. v2 adds two fixed texts (409, 412) that say what the client should do next; the four v1 texts are unchanged.

**Why a `/v2` path rather than extending v1.** The spec fixes the four operations byte for byte and `ApiDocsTest` enforces it, so v1 cannot gain fields: adding `details` to `InventoryItem` changes every v1 response, and `POST /inventory/{skuId}` means "add stock" with a fingerprint of `quantity`, so it cannot also mean "create with details" without changing the replay rules keyed clients rely on. A path version is visible in curl, logs, the Swagger UI and the proxy rules, which header (`Accept: application/vnd…+json; version=2`) or query-parameter versioning is not, and it lets the request guard and the Accept filter match by prefix. Rejected: extending v1 with optional fields (changes the frozen contract), header versioning (invisible, and springdoc documents it poorly), a separate `/skus` resource (two names for one thing). Cost accepted: two representations of a SKU that must not drift, kept honest by `SkuItem.quantity` coming from the same `sku.quantity` column and a test that the v1 and v2 list agree row for row.

**Request guard and Accept.** The routed-path checks of C3 apply to v2 the same way (`;` in the SKU segment, POST/PUT Accept q=0 → 400, body cap), with a 64 KB body cap for the two v2 writes, enforced on the Content-Length and, for a chunked body, on the bytes as they are read, since a v2 body is materialised before the record's own limits run (the spec POSTs keep A19: their body is streamed, so a chunked v1 body is not capped). The cap is sized for the largest contract-valid body on the wire: a client may escape every non-ASCII character of the name and description as six-byte `\uXXXX` (12,720 bytes for 2,120 characters), plus ten 2,048-byte ASCII URLs and the structure, about 33.4 KB (tested). GET on v2 ignores Accept as U2 says.

**Superseded by this section.** Nothing above §8 is superseded; §7 still lists what V2 changed from `main`. G6's list of error texts gains the two v2 texts for the v2 operations only.

## 9. Redis removed: Postgres only

**Owner decision** (review of PR #74, 2026-09-28), recorded as given: "The design choice is because I wanted to horseshoe Redis in to show off and I realized that implementing Redis wasn't worth it. I had concerns with the v1 design because it seemed to add unnecessary complexity because of having to maintain a cache and database. But I also wanted to display my experience with in-memory/cache dbs. Upon reflection the experience would have taught me it isn't necessary for this scenario but could be helpful during intense sell scenarios where every process counts."

**What Redis did, and what replaces it.**

| Use (§1–§3) | Replaced by | What changes for a client |
|---|---|---|
| `stock:{skuId}` count cache, TTL 5 s, versioned post-commit refresh, `onlyIfPresent` | `GET /inventory/{skuId}` reads the `sku` row: one primary-key lookup at READ COMMITTED autocommit, which never waits on a writer (a purchase's row lock blocks writers, not readers) | A read is always the last committed count. The staleness bound of §3 (TTL plus a read miss) disappears; there is nothing to tune. |
| `idem:{key}` replay copy with the row's remaining validity | The Postgres claim and the stored row (§2 step 3), which were the mechanism all along; a repeat with a key reads one row by primary key | Same responses, same 24 h validity (T1), now decided by one clock instead of two (A16 no longer applies). |
| `RedisGuard`, the 250 ms command timeout, the WARN rate limit, the Lua scripts, the Redis health component | Nothing | One container fewer to run; readiness is unchanged (`readinessState,db`). |

**Why this is safe.** §4 already stated that everything in Redis could be lost at any moment without breaking an invariant, and the fault tests proved it (FLUSHALL, pause, restart, all with every endpoint still correct). Removing Redis is the permanent form of "Redis down": reads miss and go to Postgres, replays go to the claim. No oversell, exactly once, durable and recorded are enforced by the conditional `UPDATE`, the `CHECK`, the claim's unique index and the ledger, all in Postgres, all unchanged. The concurrency, idempotency and durability tests keep running against Postgres alone.

**Cost accepted.** Every read of a SKU now costs Postgres one indexed row lookup instead of a Redis hash read for the SKUs read within the last 5 s. For this scenario, a single inventory service with a handful of SKUs read by people, that is the right trade: a primary-key lookup is microseconds of work on a row that is already in shared buffers, and no cache means no invalidation, no version race, no second store to monitor. The case for a cache returns when a read storm on a few SKUs (a launch, a flash sale) makes the database the bottleneck; §3's design (versioned refresh, TTL as the staleness bound, populate on read only) is the one to bring back then, and its tests are in the history (v2 9afd14a…85bc2e0).

**What is removed.** The `cache` package (`StockCache`, `ReplayCache` in `idempotency`, `RedisGuard`, `AfterCommit`, `CacheConfiguration`, `CacheProperties`), `stock-set.lua` and `replay-put.lua`, `spring-boot-starter-data-redis`, `spring.data.redis.*` and `inventory.cache.*`, the compose `redis` service and the app's dependency on it, `RedisFaultTest`, `StockCacheTest` and the Redis half of the test containers; `InventoryService.find` becomes the row lookup and the `@Idempotent` interceptor claims and replays through `IdempotencyStore` only. The API, `openapi.yaml` and the front end do not change.

**Alternative rejected: keep the cache.** It would have kept a second store to run, monitor and keep consistent, a staleness bound to test and a second clock for key expiry, for a read path that is one primary-key lookup; the owner judged that a wrong trade for this scenario. `sku.version` stays as it is: a change counter bumped by every stock write, read by nothing today. Dropping it would be a second migration and a change to every stock statement for one column; keeping it costs nothing at runtime and it is the optimistic-concurrency token a future `If-Match` on stock or §3's refresh would need. The YAGNI trade is stated, not hidden.

**Superseded by this section.** The title and the introduction as first written, §1 (the Redis column and "Redis is a cache in both roles"), §2 step 2 (the Redis fast path) and step 4 (post-commit refresh), §3 entirely, §4 rows about Redis, §5 alternatives 3–6 (now moot), §6 rows "Reads", "Idempotency", "Moving parts" and the Redis tuning values, A3–A9, A13, A15, A16 the Redis parts of §8 "Reads" (v2 never used the cache; now nothing does), and the §7 rows for D8/S4 and D10 (marked there). Everything else, including the rest of §7 and §8, still applies.

## 10. Idempotency-Key as an explicit call

**Decision** (issue #81, A33, A34). The keyed POSTs no longer go through spring-aop advice. `InventoryService.add`, `purchase` and `create`:
1. parse the Idempotency-Key (`IdempotencyKey.parse`; a malformed key, `""` included, is 400);
2. check the skuId (create 400, purchase 404);
3. without a key, run the write in their own READ COMMITTED `TransactionTemplate`, as before;
4. with a key, pass the write to `IdempotencyStore.run(key, operation, skuId, canonicalRequest, write)`.

Neither rejection is stored. `run` opens a READ COMMITTED transaction (`PROPAGATION_REQUIRED`), or joins a READ COMMITTED or DEFAULT caller's transaction and refuses any other isolation (`IllegalStateException`); when joined, the caller's `now()` (T1) and rollback scope apply, so the key expires by the caller's transaction start and a failed write marks the caller's transaction rollback-only. A stricter caller is refused because the claim would run at its isolation, where a concurrent claim of the same key fails with 40001 instead of replaying. In that transaction, `run` claims the key, runs the write and stores its response (§2 step 3), or replays or rejects: a different operation, skuId or request (S8), a key older than 24 h (T1), or a response cleared by the README's retention clean-up (A18).

**Why.** Z1 put the advice between `@Retryable` and a SERIALIZABLE transaction, so each retry attempt had to re-claim in a new transaction; the proxy order [Retry, Idempotency, Tx] was the point. §7 removed the isolation and the retry, and the advice was left with nothing to order. What remained cost more than it gave:
- a pointcut that checked method signatures by reflection;
- an interceptor that read arguments by position;
- a generic result strategy looked up per method, with one implementation;
- a skuId check run twice, once by the advice and once by the service;
- a `REQUIRES_NEW` that would have suspended a caller's transaction instead of joining it.

The explicit call puts the order of checks in one method a reader can follow.

**Unchanged.** The claim, replay and reject rules and the stored bytes (Y4) are unchanged, and so is the 24 h expiry by the database clock. The stock POSTs' canonical request is still the quantity's digits and the create's is still `CreateSku.fingerprint()`, so rows stored before this change replay unchanged (Y3). The outcome is still rendered to a `StoredResponse` in the web layer: the domain declares `KeyedResponses` and `OutcomeResponses` implements it, so the domain package still imports no HTTP type (Z2). No API, `openapi.yaml`, migration or front-end change.

**Rejected alternatives.**
- **Keep the advice with `REQUIRED`.** That fixes the propagation but keeps the positional contract, the reflection check and the double skuId check.
- **Store a domain outcome instead of an HTTP response.** That would drop `content_type` and the status CHECK, so it needs a migration and a new replay format (Y4). **Deferred as a future improvement, #83** (owner decision, A35; §11). Why it waits: every keyed caller today is the HTTP controller, so it changes no behaviour; it trades Y4's byte-for-byte replay for re-rendered replays; and it needs a two-format migration on a table whose rows are never purged (R9), including the A18 tombstone rule and the README clean-up. #83 names the triggers for revisiting it (a non-HTTP caller needing keyed writes, a response-format change replays should follow, measured lock-hold cost from rendering inside the transaction) and the proposed approach: decisions first; an additive V4 migration with outcome columns and a two-format CHECK; a domain outcome codec behind `IdempotencyStore.run`; rendering after the transaction; and a legacy read path kept for 24 hours.

**Superseded by this section.** The §2 wording "advice around the service method (Z1 kept)", the §7 Z1 row as first written, the §9 phrase "the `@Idempotent` interceptor claims and replays", A10 and A29 (the `Fingerprinted` contract; the canonical form it defined stays).


## 11. Designed, not built: future improvements

Two changes are deferred on purpose. Each has an issue holding the full reasons, the triggers for revisiting it, the proposed approach and acceptance criteria. Neither changes anything above.

**Store a domain outcome instead of the HTTP response (#83, A35).** Summarised in §10's rejected alternatives.
- **Why it waits:** every keyed caller today is HTTP; it would trade Y4's byte-for-byte replay for re-rendered replays; and it needs a two-format migration on a never-purged table, including the A18 tombstone rule and the README clean-up.
- **Approach:** decisions first; an additive V4 migration; a domain outcome codec behind `IdempotencyStore.run`; rendering after the transaction; and a 24 h legacy read path.

**Messaging: an outbox to Kafka for ERP sync, high throughput and flash sales (#85, A36).**

*Why there is no broker.*
- **The contract is synchronous.** The spec's operations return the final outcome with fixed codes (G10); there is no `202` and no status resource, and a `GET` right after a `POST` must show the change.
- **Correctness is one transaction.** The claim, the conditional `UPDATE` and the ledger row commit together (§2, §10), so a broker in the write path would be a dual write.
- **Idempotency stays.** A consumer that changes Postgres is at-least-once and still needs the `idempotency_keys` claim.
- **No consumer exists.** Nothing in this scope reads stock events.
- **The §9 lesson applies.** A second system with no correctness benefit costs more to run than it returns.

*Concurrency.* Postgres coordinates every concurrent write, so a queue would not fix a correctness problem. What holds today, each backed by a test at 8 threads:

| Scenario | Mechanism | Result | Test |
|---|---|---|---|
| Purchases of one SKU | Conditional `UPDATE` waits on the row lock, then re-checks `quantity >= :q` on the new row version (§2); `CHECK` backs it | Never oversells | `InventoryConcurrencyTest`, `InventoryHttpConcurrencyTest` `concurrentPurchasesNeverOversell` |
| Adds to one SKU | Same row lock | No lost update | `concurrentAddsAreNeverLost` (both classes) |
| Writes to different SKUs | Different rows, no shared lock | Run in parallel | `CrossSkuConcurrencyTest` |
| One Idempotency-Key, sent twice at once | The second claim blocks on the primary key until the first commits, then replays or rejects (§10) | One stock change | `IdempotencyHttpConcurrencyTest` |
| Creates of one new SKU | `INSERT … ON CONFLICT` on the primary key (§8) | One 201; the rest 409, or a concurrent spec add lands on the created SKU | `SkuDetailsConcurrencyTest` |
| PUT during a purchase | The PUT's KEY SHARE is compatible with the purchase's FOR NO KEY UPDATE (§8) | Neither waits | `aPutCompletesWhileAPurchaseHoldsTheRowLock` |
| Conditional PUTs | `version = ANY(:expected)` in one statement | Exactly one applies | `concurrentConditionalPutsApplyExactlyOne` |
| Reads | MVCC snapshot at autocommit (§9) | Never wait on a writer | `findNeverWaitsOnAWriterHoldingTheRowLock` |
| Deadlocks | One lock order in every transaction (§2) | None possible | By construction |
| Several app instances | The app keeps no state; Postgres coordinates | Same guarantees | `DurabilityAcrossServiceInstancesTest` (durability only, not concurrent cross-instance writes) |

Two accepted races change only which error text is returned, never the stock: the purchase's "Insufficient inventory" versus "SKU not found" around a concurrent create (§2), and a conditional PUT's 404 versus 412 (§8).

The limits are throughput, not correctness:
- **A hot SKU serializes.** Its writes run one at a time, so its throughput is roughly 1 / lock-hold time. A keyed write holds the lock longer, because the response is rendered and stored inside the transaction (#83 would move rendering out).
- **Pool starvation.** `application.yaml` sets no pool or thread sizes, so the defaults apply: Hikari has 10 connections with a 30 s `connectionTimeout`, and Tomcat has 200 threads. Writers waiting on a hot SKU's lock hold connections, so requests for other SKUs wait up to 30 s for one and then fail with 500.
- **No bounded wait.** There is no `lock_timeout` or `statement_timeout`, so a waiter waits as long as the lock holder takes.
- **Unmeasured.** The concurrency tests prove correctness at 8 threads; there is no load test.

Hardening that needs no broker and no contract change is flash-sale option 1 below: size the pool and its timeout on purpose, set `lock_timeout` and `statement_timeout` so overload answers with a quick 500 instead of a 30 s wait, and add a load test. A queue helps only past that point: one consumer per skuId partition applies purchases in order with no lock wait and can batch them into one transaction, at the price of an asynchronous contract (option 3).

*Where it would help.*
- **ERP integration.** Outbound, stock-change events come from the append-only ledger, with `sku.version` as a per-SKU ordering token. Inbound, adjustments arrive as messages keyed by message ID, which needs #83. Two things must come first: deciding which system owns stock, and a reconciliation report.
- **High throughput.** Writes to different SKUs already run in parallel. Writes to one SKU serialize on its row lock, and waiting writers hold Hikari's default 10 connections. Nothing has been measured, so a load test comes first.
- **Flash sales.** Oversell stays impossible and sold-out rejections are cheap, but purchases of the hot SKU queue on one lock. The options, from least to most contract change:
  1. shorten the lock hold (#83's rendering move, pool sizing, `lock_timeout`);
  2. shard the SKU's stock across bucket rows with `SKIP LOCKED`;
  3. an admission queue with reservations, as a new versioned `202` endpoint with batched per-partition decrements (v1 unchanged);
  4. a waiting room at the edge.

*Tradeoffs.* A broker brings:
- eventual consistency for consumers;
- an outbox or CDC, to avoid dual writes;
- at-least-once delivery plus deduplication;
- per-partition ordering only;
- new failure modes: broker down, consumer lag, poison messages;
- brokers, schemas and Testcontainers Kafka to operate and test.

In return it gives fan-out, decoupling and batching.

*Approach.*
1. Measure first.
2. Record the decisions: source of truth, event schema, and any async endpoint as a new API version.
3. Publish events out through a transactional outbox (or CDC on `inventory_ledger`) to an `inventory.stock-changed` topic keyed by skuId, with no contract change.
4. Take events in through an idempotent consumer with a DLQ, after #83.
5. Apply the flash-sale options only if the measurements call for them.
