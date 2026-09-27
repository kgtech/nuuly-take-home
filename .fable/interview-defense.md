# Interview defense

Questions a senior interviewer would ask about V2, from the self-critique's interview-defense review (`.fable/critique/intv.md`), each with a short answer grounded in the final code and records. Then the three weakest points and how to answer them.

## Storage design

1. **Why a balance row instead of `main`'s SUM-ledger?** A SUM grows with history and, under SERIALIZABLE, its predicate makes writers on unrelated SKUs conflict (C-03). One row per SKU updated conditionally costs the same at any history size, needs no retries, and the ledger still records every change. `DESIGN-V2.md` §5 alt. 2, §6. No speed claim is made; the difference is structural.
2. **Why READ COMMITTED with no retries, and why is it safe?** The `UPDATE ... WHERE quantity >= :q` waits on the row lock and Postgres re-evaluates the WHERE against the new row version after the wait, so the second of two concurrent purchases of the last unit sees 0 and updates nothing. `CHECK (quantity >= 0)` is the backstop. §2 "Why READ COMMITTED is enough".
3. **Can two writers deadlock?** No: every transaction locks at most one idempotency row, then one `sku` row, then a KEY SHARE on that same row; same order everywhere. §2.
4. **What is Redis trusted with?** Nothing authoritative: a copy of counts for one TTL and a copy of completed idempotency rows for their validity. Every write goes to Postgres first; every Redis failure is a miss (`RedisGuard`). §1, §4, A3, A7. Replayed content is validated against what the row's CHECK could have stored (A19-adjacent, `ReplayCache`).
5. **What does the Redis idempotency copy buy you?** Replays and mismatches answered without a Postgres round trip and without touching the claim row. Honest tradeoff: the brief asked Redis to take part in idempotency; Postgres-only would be simpler by a class, a script and a fault path, and is recorded as the rejected alternative (§5 alt. 6, A13). No throughput figure is claimed.
6. **Exactly once after Redis loses the key?** The Postgres claim (`INSERT ... ON CONFLICT DO NOTHING RETURNING`) lives in the stock transaction; a miss in Redis always goes there. Tested through FLUSHALL, pause and a real container restart (`RedisFaultTest`).
7. **Two concurrent requests with the same fresh key?** The second blocks on the primary key until the first commits, then reads the completed row and replays it (READ COMMITTED gives it a fresh snapshot). `IdempotencyHttpConcurrencyTest`.
8. **How do you pick the hot SKUs?** The hot set is the SKUs read within the last TTL: an entry lives one TTL from its populate, a hit doesn't renew it, a write refreshes the value but keeps the remaining TTL. LFU eviction is a memory backstop, not the selector. §3 (reworded after the critique found the earlier "stays while read" wording wrong).
9. **How stale can a read be, and why 5 s?** At most the TTL measured from the read that cached it, in normal operation milliseconds (post-commit versioned refresh). 5 s makes a read storm cost Postgres 0.2 reads/s per SKU and keeps a wrong count shorter than a page load; configurable. §3, §6 "Tuning values".
10. **Why refresh with versions rather than invalidate?** One round trip and a tighter bound; versions make out-of-order refreshes harmless. §5 alt. 5, A5.
11. **Redis down, slow, flushed, restarted; Postgres down; crash between writes?** §4 table, tested in `RedisFaultTest`. Slow Redis costs up to 250 ms per call, three calls on a keyed POST (750 ms measured). Postgres down: cached reads answer until their TTL, everything else 500, readiness DOWN. Crash after commit: stale cache for one TTL, absent replay copy; no torn state because Postgres is the only write that must succeed.
12. **Why not a circuit breaker?** The design already falls back on every miss; a breaker would only shorten the 250 ms wait. A3.
13. **Why is `quantity = SUM(deltas)` a test and not a trigger?** The conditional UPDATE must stay the single statement that decides and returns the balance; a maintaining trigger would move that decision into PL/pgSQL. Asserted by `Invariants.balanceMismatches`. §5 alt. 7, A14.
14. **Why a trigger for append-only rather than privileges?** No role separation exists in this deployment; a trigger is enforced for every connection. A11.
15. **Idempotency rows forever?** T1 needs the row to reject an old key. Operators clear the response columns (README Operations); a cleared row still answers 400 (A18). Growth figures in the README.
16. **Why must the key be a UUID?** A `uuid` column has no length or encoding surface and the front end generates v4 UUIDs; Stripe and the IETF draft accept any string, and that divergence is documented (`format: uuid`, A17).
17. **Why 400 for a reused key with a different body, not 422/409?** The spec lists no 422 or 409 (G14); a concurrent duplicate blocks then replays instead of 409.
18. **Why AOP for idempotency now that retry is gone?** Honest answer: the shape was kept from Z1 to keep the controller and service free of idempotency code, but without the retry layer a direct call would be simpler; recorded in A10 as a kept shape, and this is one of the weak points below.

## API and front end

19. **Why 404 for a malformed skuId on GET/purchase but 400 on create?** The spec lists no 400 for GET; a malformed id can't exist (G11, S2).
20. **Why is a bad `limit` ignored rather than 400, and why 250?** R4/R8/C2: the list must never fail on paging noise; 250 is Shopify's maximum and the default page so an unparameterised call still returns up to the cap.
21. **How does the front end prevent a double charge?** One UUID per user action, reused while in flight, after a network failure, a timeout and any 5xx/408/429 (a proxy's 502 included), dropped only on 2xx or another 4xx or when the inputs change. `useIdempotentSubmit`, FE9, e2e retry flow.
22. **Why does client validation never block a request?** The server's answer is the contract; the client blocks only what the server is certain to reject and shows the server's own texts (FE10).
23. **How does a contract change fail the front-end build?** Types are generated from `openapi.yaml`; `npm run check:api` fails on a diff (FE5, FE23).

## The three weakest points, and how to answer

1. **The Redis idempotency copy adds complexity for a rare case.** Say so: it exists because the brief asked Redis to take part in idempotency; the simpler Postgres-only design is recorded as rejected for that reason, and everything that keeps the copy honest (one `KEY_VALIDITY` constant, `created` field, age check, content validation) is small and tested. If asked what you would remove first, this.
2. **The hot-SKU story is a TTL, not a popularity mechanism.** Own the correction: the first draft said "stays while read"; the critique caught it; the design now states the hot set is "read within the last TTL" and why renewing on hits would break the staleness bound. LFU is a backstop.
3. **Two open test gaps from the critique.** No test drives the Redis replay fast path (the verifier saw it work live: `PTTL idem:<key>` 86,399,644 ms) and no test restarts the service context to show durability (true by construction: one Postgres commit before the response). Both are listed as open in `.fable/critique.md` and `FABLE_REPORT.md` rather than claimed.
