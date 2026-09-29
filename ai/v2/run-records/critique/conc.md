# Critique — conc (Concurrency and data integrity) — v2 @ d01852a (reviewer report)

## Findings
- F-conc-01 MAJOR — InventoryConcurrencyTest.runTogether waits unboundedly (try-with-resources executor close, future.get()); #57 AC4 unmet; a stuck writer hangs the build. Fix: use Concurrently.run.
- F-conc-02 MAJOR — #35 "Durable" AC requires a service-context restart test; none exists (only the Redis restart). A test writing through one context and reading through a fresh one is missing.
- F-conc-03 MINOR — Purchase create/purchase race: UPDATE (no row) then EXISTS in separate snapshots; a concurrent create committing between them yields 400 "Insufficient inventory" for a SKU that did not exist at UPDATE time; with a key that 400 is stored for 24 h. Accept-and-document, or lock/check existence first.
- F-conc-04 MINOR — #57 AC3: same-key concurrency tests neither assert nor explain that a request blocked on the claim (sequential replays pass identically).
- F-conc-05 MINOR — IdempotencyStoreTest at SERIALIZABLE vs production READ COMMITTED.
- F-conc-06 MINOR — Replay-cache expiry mixes the Postgres clock (created_at) with the JVM clock; skew Δ extends a key's Redis life past T1. Compute remaining in SQL or document the assumption.
- F-conc-07 NIT — Append-only triggers do not cover TRUNCATE (deliberately, for tests); "enforced by a trigger" slightly over-stated.

## Checked and found met
No oversell (UPDATE WHERE + CHECK, M×200 with distinct quantities, N−M×400, SUM 0); lost adds impossible (INSERT ON CONFLICT then UPDATE; totals 1..8, one row); overflow with :max = Long.MAX_VALUE; ledger in the same transaction only when a row came back; crash between statements → one transaction, forced ledger failure → 500 with nothing written and the key reusable; READ COMMITTED claim: loser waits then sees the completed row; deadlock freedom (one key row then one sku row, one connection per request); two-instance argument holds by construction (no test, F-conc-02); staleness race bounded by the TTL from the read; Redis faults → misses; 8 threads with latch and bounds in Concurrently.
