# Decisions added during the V2 run

One line of reasoning each. Storage decisions are in `DESIGN-V2.md`; front-end ones in `frontend/DECISIONS.md`.

- [A1] Branches are `v2-<slug>`, not `v2/<slug>`: git cannot create `refs/heads/v2/...` while the branch `v2` exists (cannot lock ref), so PROMPT.md's naming is unworkable as written.
- [A2] `.fable/` is committed on `v2` as PROMPT.md requires; nothing is excluded through `.git/info/exclude`, so the run's record travels with the branch.
- [A3] Redis calls fail soft through one `RedisGuard` (catch, WARN once per role per 10 s, return empty) instead of a circuit breaker library: the design already falls back to Postgres on every miss, so the only thing a breaker would add is a shorter wait, which the 250 ms command timeout already gives.
- [A4] The readiness health group is `readinessState,db` only: Redis down does not make the service unready because every endpoint keeps working (DESIGN-V2 §4); `/actuator/health` still reports the Redis component so an operator sees it.
- [A5] Cache versions come from `sku.version`, incremented in the same UPDATE as the quantity, rather than from ledger ids: the version is returned by the write that produced it, with no extra query, and a read populates with the row's own version.
- [A6] Writes refresh only existing cache entries (`onlyIfPresent`): a SKU nobody reads never occupies Redis memory, so "most used" is decided by reads alone.
- [A7] The Redis replay copy is written after commit (transaction synchronization), never inside the transaction: a rollback must not leave a response in Redis that Postgres never stored.
- [A8] Tests bind the Redis container to a fixed host port and run it with `--save "" --appendonly no`, matching compose: a real `docker restart` then keeps the address the app uses and comes back empty, which is what the durability test needs to show.
- [A9] The test cleanup (`Tables.reset`) truncates the tables and FLUSHALLs Redis in one call: a cached count must never outlive the rows it copies, and a helper that only truncated let stale counts leak between tests.
- [A10] The idempotency advice keeps Z1's shape (advisor + interceptor) but at READ COMMITTED with REQUIRES_NEW and no retry: a concurrent claim blocks on the primary key instead of raising 40001, so the retry machinery had nothing left to do.
- [A11] The append-only trigger raises SQLSTATE P0001 (plpgsql `RAISE EXCEPTION`) rather than a privilege error: the tests assert the state, and no role separation exists in this deployment to make 42501 truthful.
- [A12] CI (as written in `.fable/ci-workflow.yml`, see DEVIATIONS.md) runs on `ubuntu-latest` with Temurin 25 and Testcontainers against the runner's Docker; the front-end job runs `npm ci`, lint, typecheck, test and build in `frontend/` (it presumes the front end is present, which it is once PR #62 merges).
