# Critique — storage (Storage design) — v2 @ d01852a (reviewer report)

## Findings
- F-storage-01 MAJOR — `inventory.cache.replay-ttl` is a free knob while the 24 h lives hard-coded in IdempotencyStore SQL; DESIGN §2 "an expired key always falls through to Postgres" holds only while replay-ttl == 24h. Repro: run with INVENTORY_CACHE_REPLAY_TTL=48h, key used, backdate row and Redis `created` by 25h → Redis replays 200; after FLUSHALL → 400. Also app-vs-DB clock skew δ. Fix: one constant shared by SQL and cache (or bind the SQL interval to the property) and a test asserting the coupling.
- F-storage-02 MINOR — §4 Redis-slow bound: a successful keyed POST makes three Redis calls (lookup, refresh, put) → 750 ms, not 500; an unkeyed POST pays one (refresh), unlisted; the refresh runs even when no entry exists.
- F-storage-03 MINOR — #35 AC "service context restart" untested (only the Redis restart half).
- F-storage-04 MINOR — The Redis replay fast path has no test that fails if it is dead (lookup always empty / put never writes → suite green); untested: hit → replay without Postgres, mismatch → 400 without Postgres, remaining-TTL PEXPIRE, put skipped when expired, created epoch written.
- F-storage-05 MINOR — StockCacheTest staleness test tolerates 4× the bound (atMost 3 s for 700 ms); sleep TTL + margin then one find must return the new value.
- F-storage-06 MINOR — "Most used"/"stays while it keeps being read" (DESIGN:49, README:110) doesn't match TTL semantics (a hit never renews; entry lives one TTL from populate; hottest SKU misses to Postgres once per TTL); §3 line 45 says a refresh PEXPIREs while line 47 and the script say it doesn't.
- F-storage-07 MINOR — `.fable/current-implementation.md` pins main at 317ab0c and says C-05 is open, but main is now 8c3c2b4 (PR #34 merged; C-05 fixed there). Say which SHA the doc describes.
- F-storage-08 NIT — Data errors (malformed hash, Lua error) are logged as "Redis unavailable".
- F-storage-09 NIT — IdempotencyStoreTest javadoc/template still SERIALIZABLE (X1/Z1 superseded).

## Checked and found met
No oversell (UPDATE WHERE + CHECK; M-of-N at service and HTTP; cross-SKU); exactly once (claim in the stock transaction, loser blocks then reads committed row or re-claims after rollback; 8 concurrent same-key; FLUSHALL/pause/restart); durable/recorded on the Postgres side (one transaction, trigger, quantity = SUM asserted); bounded staleness (monotonic script, populate sets TTL, refresh keeps it, race bounded by one TTL from populate); defined failure (every Redis call incl. after-commit hooks guarded, 250 ms timeouts, readiness excludes Redis, one WARN per role); after-commit hooks on the REQUIRES_NEW transaction; lock ordering claim → sku → ledger, no cycle; EVALSHA→EVAL fallback after restart; no performance claims; compose Redis flags match the tests.
