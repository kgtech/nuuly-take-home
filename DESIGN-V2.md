# DESIGN-V2: stock in Postgres rows, hot counts and replays in Redis

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
1. `INSERT INTO sku (sku_id, quantity, version) VALUES (:id, 0, 0) ON CONFLICT DO NOTHING`.
2. `UPDATE sku SET quantity = quantity + :q, version = version + 1 WHERE sku_id = :id AND quantity <= 9223372036854775807 - :q RETURNING quantity, version`. No row → `Overflow` → 400 "Invalid request" (G12, U1). The row lock serializes writers on this SKU; writers on other SKUs never conflict.
3. `INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) VALUES (:id, :q, 'add')`.
4. Commit. After commit (transaction synchronization): `stock:{skuId}` is refreshed **only if it exists** and only if `version` is newer than the cached one (one Lua script, section 3). Redis failure here is logged and ignored.

**Purchase** (`POST /inventory/{skuId}/purchase`), one READ COMMITTED transaction:
1. `UPDATE sku SET quantity = quantity - :q, version = version + 1 WHERE sku_id = :id AND quantity >= :q RETURNING quantity, version`. A row → success. No row → `SELECT 1 FROM sku WHERE sku_id = :id`: missing → 404 "SKU not found", present → 400 "Insufficient inventory".
2. `INSERT INTO inventory_ledger (..., -:q, 'purchase')`.
3. Commit, then the same post-commit cache refresh as add.

**Repeated `Idempotency-Key`** (either POST). The key is handled by the idempotent-receiver advice around the service method (Z1 kept), in this order:
1. Body validation and key format as on `main` (S3, U3); a malformed key is 400 and never stored.
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

**Cache script** (`stock-set.lua`, one round trip, atomic): given `q`, `v`, `ttlMillis` and `onlyIfPresent`: if the key is absent and `onlyIfPresent`, do nothing; else if the key is absent or its `v` < the new `v`, `HSET q v` and `PEXPIRE ttl`; else leave it (a stale writer never overwrites a newer value). Reads call it with `onlyIfPresent=false`; the post-commit write hook calls it with `onlyIfPresent=true`.

**Staleness bound.** A cached count is refreshed after every committed change to that SKU while the entry exists, so in normal operation a read is stale for the milliseconds between the commit and the post-commit `HSET`. The **guaranteed bound is the TTL, `inventory.cache.stock-ttl` (default 5 s), plus the latency of one read miss**: if the post-commit refresh fails (Redis down or slow, or the service crashes right after commit), the stale entry expires within the TTL and the next read goes to Postgres; and a read miss that populates with a row it fetched just before a concurrent commit (whose refresh found no entry and skipped) caches that row for one TTL, so the bound is the TTL measured from the read, not from the commit. Versions make the refresh monotonic, so two writes whose refreshes arrive out of order can't leave the older count behind past the TTL either: the later version wins immediately. The TTL is set only when a read populates; a refresh keeps the remaining TTL, so an entry stays only while it keeps being read. The bound is tested by writing directly to Postgres (bypassing the hook) and asserting the read is correct after the TTL.

**Which SKUs are "most used".** V2 does not keep a separate popularity structure. A SKU enters the cache when it is read, stays while it keeps being read (each read miss re-populates, each write refreshes an existing entry, the TTL is renewed only on a populate), and leaves when its TTL passes without a read or when Redis evicts it. Redis is configured with `maxmemory` and `maxmemory-policy allkeys-lfu` (compose), so under memory pressure the least frequently read SKUs are evicted first: "most used" is what LFU keeps. The rest are read from Postgres (one indexed row lookup) and are correct, only slower. Writes never populate the cache (`onlyIfPresent`), so a SKU nobody reads never occupies memory.

## 4. Failure modes

| Failure | Behaviour |
|---|---|
| Redis down or unreachable | Every endpoint keeps working. Reads miss and go to Postgres; writes commit in Postgres and skip the post-commit refresh; the idempotency fast path is skipped and the Postgres claim decides. Redis calls use a 250 ms command timeout; each failure is logged at WARN (rate-limited to one line per 10 s per role) and the request continues. No 5xx is caused by Redis. |
| Redis slow | Same as down after the timeout. A read miss pays up to two timeouts (lookup, then populate), a keyed POST up to two (lookup, then the after-commit copy): at most 500 ms added, then Postgres answers. |
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

## 6. Comparison with `main`

| | `main` | V2 |
|---|---|---|
| Balance | SUM of ledger deltas per request | one row, updated in place |
| Isolation | SERIALIZABLE + up to 10 retries on 40001/40P01 | READ COMMITTED, row lock; no retry logic |
| Cross-SKU interference | SSI false conflicts (C-03) | none (different rows) |
| Ledger | append-only by convention (C-10 open) | append-only by trigger |
| Reads | SUM in a read-only transaction | Redis hit, else one row lookup |
| Idempotency | Postgres claim + replay | Postgres claim + replay, Redis fast path |
| Moving parts | Postgres | Postgres + Redis, cache script, post-commit hook |

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
| Z1 (isolation clauses only) | The idempotency advice keeps its structure and REQUIRES_NEW, but at READ COMMITTED; the "not SERIALIZABLE → IllegalStateException" guard is removed. |
| R2 (40001 clause) | A concurrent claim blocks on the unique index instead of raising 40001; after the first commits, the second replays. |
| D8, S4 (compose content) | compose.yaml runs Postgres **and Redis**; the app depends on both. |
| D10 | Package layout gains `cache/` (Redis) beside `inventory/` and `idempotency/`. |
| Issues #24 AC3, #26 AC1–3, #29, #30 (test-suite items written against `main`'s tests) | Their intent is met by construction in V2 (one shared container per store, no SUM query plan to EXPLAIN, no SSI conflicts); the V2 issues restate them for V2's tests. |
| L-26 (retry lesson content), L-33 (cross-SKU SSI) | No longer apply without SERIALIZABLE; the cross-SKU concurrency test is kept as a regression guard. |
