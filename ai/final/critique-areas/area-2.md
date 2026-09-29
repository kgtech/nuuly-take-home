# Area 2: concurrency and data integrity (SHA 436cacae24a4dc4db80a03f520843b1ca3d9bbb9)
Live stack (compose project crit2, APP_PORT 18202), Python load client in /private/tmp/claude-501/critique/s2 (lib.py, t1..t8.py).
No BLOCKER or MAJOR found. Every invariant I attacked held. Two MINOR and one DECISION CHALLENGE below.

### F-2-01 [MINOR] A stalled row lock exhausts the 10-connection pool: unrelated SKUs and reads answer 500 after 30 s
- Location: application.yaml (no spring.datasource.hikari.* / lock_timeout / statement_timeout); every write path in InventoryService holds a pooled connection while waiting on the sku row lock.
- What is wrong: writes to one hot or locked SKU block on the row lock inside a transaction, each holding one Hikari connection (default max 10, connectionTimeout 30 s). Once 10 writers wait, every other request (writes to OTHER SKUs and even GET /inventory/{id} on other SKUs, which need a connection) queues and then fails with 500 "Internal server error" after 30 s. There is no lock_timeout, so the outage lasts as long as the lock holder (a stuck session, a long migration, an operator's psql). The app recovers by itself once the lock is released (verified). Nothing lost or doubled; balance stayed correct.
- Evidence: t6.py. A psql session held `update sku ... where sku_id='LK'` for 45 s; 55 requests fired at 1 s:
  ```
  ('lk-get', 200, 0) 2     ('lk-get', 500, 30) 3
  ('lk-purchase', 200, 40) 10   ('lk-purchase', 500, 30) 20
  ('other-add', 500, 30) 10     ('other-get', 200, 0) 4   ('other-get', 500, 30) 6
  after 200 (200, '{"skuId":"OTHER","quantity":99}')   mismatches: 0
  ```
  App log: `ERROR ... Unhandled exception on POST /inventory/LK/purchase` (one ERROR per failed request).
- Suggested fix: set a short spring.datasource.hikari.connection-timeout and a Postgres lock_timeout (e.g. 5 s via connection init SQL); or document it in "Designed, not built".

### F-2-02 [MINOR] "Append-only by trigger" is bypassable by the app's own role (superuser); TRUNCATE is unguarded; sku.quantity has no DB-level tie to the ledger
- Location: V1__inventory.sql (row triggers only, BEFORE UPDATE OR DELETE); compose.yaml (POSTGRES_USER=inventory is a superuser and is the app's role).
- What is wrong: the app connects as a superuser. As that role, `TRUNCATE inventory_ledger` succeeds (comment says tests need it, but production has no guard), `ALTER TABLE ... DISABLE TRIGGER` then UPDATE succeeds, `SET session_replication_role=replica` lets DELETE through, and a direct `UPDATE sku SET quantity=quantity+5` succeeds (only CHECK >= 0 guards it), silently breaking sku.quantity == SUM(ledger). Ordinary UPDATE/DELETE on ledger and DELETE on sku/sku_details are correctly refused (P0001). Interview answer for "how do you know the ledger is immutable" is therefore "only against the API, not against the DB owner".
- Evidence (all rolled back in a txn except the replica-role delete, which I used once and repaired):
  ```
  begin; truncate inventory_ledger; select count(*) ...  -> TRUNCATE TABLE / 0 rows
  begin; alter table inventory_ledger disable trigger inventory_ledger_append_only; update inventory_ledger ... -> UPDATE 1
  update sku set quantity=quantity+5 where sku_id='IDA' -> UPDATE 1; mismatch count 1
  update inventory_ledger set ... -> ERROR: UPDATE on inventory_ledger is not allowed   (normal path OK)
  ```
- Suggested fix: run the app as a non-owner role with INSERT/SELECT on the ledger and UPDATE only on sku(quantity, version) (and a statement-level TRUNCATE trigger); or record it as a known limit.

### F-2-03 [DECISION CHALLENGE] Idempotent replay returns the stored snapshot, including stale `details`
- Location: OutcomeResponses.toStored / IdempotencyStore.replay (Y4).
- What is wrong: a /v2 add/purchase stores the whole 200 body, including `details` read in that transaction. A retry with the same key after a PUT details (or after other stock changes) replays the OLD details (and old quantity). This is the intended "same response" contract, but a client that retries after a details edit sees a body inconsistent with GET; the ETag is not in that response so it cannot be used for If-Match from a replay. Same for a stored 400 "Insufficient inventory" replayed after a restock.
- Evidence: t2.py (g): replay of a purchase returns `{"quantity":0}` unchanged; by code reading for details. Not a bug against the spec ("replay stored status and body").
- Suggested fix: none required; document that replayed bodies are point-in-time and clients must GET before If-Match.

## Checked and fine (what I actually tried, with results)
- No oversell across versions (t1): 20 SKUs x stock 10 x 30 purchases each, half via unversioned, half via /v2 with unique keys, 64 threads: exactly 10 x 200 per SKU, final quantity 0 everywhere, 200 ok / 400 rest, ledger 220 rows for 20 SKUs (20 adds + 200 purchases); sku.quantity == SUM(ledger) for all rows.
- Exactly once (t2): 50 concurrent same key/same body: 50 x identical 200, stock +5 once. Same key with different quantity (30 concurrent): 10 x 200, 20 x 400, one ledger row. Same key across two SKUs and both operations: one winner, rest 400. Same key, stock 1, 40 concurrent purchases: all 40 replay the identical 200, one deduction. Unversioned POST with key: 400 and nothing written. Key older than 24h (aged in SQL): 400, no stock change. Tombstoned row (status/content_type/body NULL): 400. Upper/lower-case UUID of the same key replays. Overflow 400 is stored and replays.
- Crash between claim and response (t7): held the sku row lock, sent keyed purchase, `docker compose kill app` mid-request: no idempotency row survived (claim is in the same txn), stock unchanged; after restart the same key ran once (200 qty 7) and the repeat replayed 200 qty 7.
- Postgres SIGKILL and restart under 16-thread keyed-add load (t8, 600 distinct keys): 590 x 200, 10 x 500 during the outage; app readiness returned by itself; retrying the 10 failures with the same keys gave 200; final quantity 601 == ledger 601 rows == 600 keys, all completed; no double or lost add.
- Overflow (seeded sku + ledger at 9223372036854775802 via SQL): add 5 -> 200 at max; add 6, 1, 2147483647 -> 400 "Invalid request", no ledger row written; keyed /v2 add -> 400; purchase from max works; GET shows exact bigint. Request quantity 2147483648, 1e3, 1.0, "5", null, 0, -1, true -> 400.
- Details races (t4): 5 rounds x 40 concurrent PUT If-None-Match:* on a new SKU: exactly 1 x 201, 39 x 412, ETag "1". Unconditional PUT on new SKU x40: 1 x 201, 39 x 200, ETag "40". 60 mixed PUT/keyed add/purchase on a new SKU x5: qty always == adds - successful purchases. If-Match "1" x30: one 200, 29 x 412; If-Match "0" on a details-less SKU x30: one 200; If-Match list "1","2" mixed with purchases: exactly 2 puts and 3 purchases succeed. If-Match on a missing SKU: 412 and the SKU is not created (404 on GET).
- Lock order: read StockRepository, DetailsRepository, IdempotencyStore, InventoryService. Every txn takes at most one key row, then one sku row, then its child rows (ledger/details insert takes KEY SHARE on a row this txn already holds or that is compatible); no txn touches two SKUs. Under 4000 mixed requests at 400 threads (add, purchase, list, PUT on one hot SKU): 4000 x 200, no deadlock, no 40P01 in logs, pg_stat_activity showed no lock waits, no `idle in transaction`.
- READ COMMITTED subtleties: purchase 404-vs-400 second read and /v2 details read run in the same txn as the row-locking UPDATE; under races I saw only valid outcomes (404 before the SKU is created, 400 when empty). PUT-created SKU always reports quantity 0 because a concurrent add blocks on the uncommitted sku insert.
- Trigger checks: UPDATE/DELETE on ledger and DELETE on sku refused with SQLSTATE P0001; CHECKs reject delta 0 and negative quantity.
- Pool exhaustion (t6): 500 "Internal server error" text after 30 s, then full recovery, readiness UP, balances consistent (see F-2-01).
- Response rendering inside the keyed txn (toStored) can only throw on a bug (no user-controlled failure path found; SkuDetails record guards NUL, lone surrogates, control chars, bad URLs). Invisible-only names (U+200B, U+2060, U+00A0) are accepted (201) but that is cosmetic.
- Final invariant sweep after every scenario: `count of sku where quantity <> SUM(ledger)` was 0. The only mismatches were ones I created myself with a superuser `session_replication_role=replica` delete, which I repaired.
