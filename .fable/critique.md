# Self-critique of V2 (verified findings)

Reviewed tree: local integration `d01852a` of `v2-ops` + `v2-frontend` (the `v2` tip once PRs #64, #62 and #65 merge). Nine fresh reviewer subagents (spec, conc, storage, api, tests, fe, sec, intv, std; reports in `.fable/critique/*.md`) and one verifier that reproduced every BLOCKER and MAJOR against the running compose stack. Severity: BLOCKER fails an acceptance criterion, loses or corrupts data, double-charges or opens a security hole; MAJOR wrong behaviour on a realistic input or a choice with no defensible answer; MINOR maintainability or clarity; NIT style.

## Totals (verifier)

| Severity | Findings | CONFIRMED | PLAUSIBLE | REJECTED |
|---|---|---|---|---|
| BLOCKER | 3 | 3 | 0 | 0 |
| MAJOR | 13 | 12 | 1 | 0 |
| MINOR | 45 | 19 | 26 | 0 |
| NIT | 12 | 4 | 7 | 1 |
| Total | 73 | 38 | 34 | 1 |

## BLOCKER and MAJOR, with outcome

| ID | Sources | Verdict | Finding | Outcome |
|---|---|---|---|---|
| V-01 | F-fe-01, F-intv-09 | CONFIRMED (Playwright repro: 502 → new key → stock decremented twice) | Front end dropped the Idempotency-Key on any non-zero status, including a proxy's own 502 | **Fixed** on `v2-frontend` 4a572d4: key kept on status 0, 408, 429 and 5xx; dropped only on 2xx and other 4xx; unit tests per status and an e2e retry flow (`route.fetch()` then abort) asserting the same key on both requests and one stock change |
| V-02 | F-fe-04 | CONFIRMED (by rubric, #41 AC3/AC4) | No axe check, no 375 px assertions or screenshots | **Fixed** 4a572d4: `@axe-core/playwright` on list, SKU and add views in both projects (zero violations), `scrollWidth ≤ viewport`, screenshots per view |
| V-03 | PR #65 F-03 | CONFIRMED at d01852a | 24h boundary tests could not tell `<` from `<=` | **Fixed** `v2-ops` e169745: exact-24h replay inside one transaction |
| V-04 | F-spec-01, F-api-01, PR #65 F-01 | CONFIRMED (`/%69nventory/VER-1;lot=7` → 200 and a write) | Request guard keyed on the raw URI; encoded or matrix-prefixed paths bypassed it | **Fixed** `v2-ops` e169745: guard on the decoded routed segments; real-Tomcat test |
| V-05 | F-spec-02, PR #65 F-02/F-07 | CONFIRMED (`application/json;q=0, */*;q=0.1` → 200 and a write) | Accept precedence contradicted C3 / RFC 9110; only the first Accept line read | **Fixed** `v2-ops` e169745 |
| V-06 | F-api-02 | CONFIRMED (`DELETE` with `a=%zz` form body → 500 JSON) | Boot's form-content filter could reach `/error` with a JSON body | **Fixed** `v2-ops` e169745: filter disabled |
| V-07 | F-conc-01, F-tests-08, F-spec-04 | CONFIRMED | `InventoryConcurrencyTest` waited unboundedly (#57 AC4) | **Fixed** `v2-fix-critique`: uses `Concurrently.run` (30 s bounds) |
| V-08 | F-conc-02, F-storage-03, F-tests-03 | CONFIRMED | No test of durability across a fresh service context (#35 AC) | **Fixed** in PR #67 (`DurabilityAcrossServiceInstancesTest`: a second full application instance, configured by connection properties, reads the acknowledged stock, list, ledger and balance = ledger after a Redis flush) |
| V-09 | F-storage-01 | CONFIRMED by code, lowered to MINOR | `replay-ttl` was a free knob while the SQL hard-coded 24h | **Fixed** `v2-ops` e169745: one `KEY_VALIDITY` constant for SQL and Redis; property removed |
| V-10 | F-tests-01, F-storage-04 | CONFIRMED (mechanism works live: `PTTL idem:<key>` = 86,399,644 ms, fields present; no test asserts it) | Redis replay fast path untested | **Fixed** in PR #67 (`redisCopyReplaysAndRejectsWithoutPostgres`: PTTL within a minute of 24 h, all fields, replay and rejection with `idempotency_keys` truncated; `redisCopyOlderThanValidityIsIgnored`) |
| V-11 | F-tests-02 | PLAUSIBLE | #61 de-duplication claimed "by construction" but not done; `@Hidden` test missing | **Partly fixed** `v2-fix-critique`: `ApiDocsTest#catchAllHandlerIsHidden`; the duplicate matrices remain (**won't fix in this run**, recorded in DEVIATIONS.md) |
| V-12 | F-fe-02 | CONFIRMED by code | `crypto.randomUUID()` outside a secure context killed the form | **Fixed** 4a572d4: `getRandomValues` fallback, `busyRef` reset in `finally` |
| V-13 | F-fe-03 | CONFIRMED by code | `key={skuId}` remounted the form on every SKU keystroke | **Fixed** 4a572d4 |
| V-14 | F-fe-05 | CONFIRMED | e2e double-submit test never checked the header | **Fixed** 4a572d4: UUID v4 header asserted; retry flow added |
| V-15 | F-fe-06 | CONFIRMED (2.2:1) | Dark-mode button contrast failed WCAG AA | **Fixed** 4a572d4: `--on-accent` token (≈9:1) |
| V-16 | F-intv-01 | CONFIRMED (record gap) | No recorded reason for the Redis idempotency copy | **Recorded**: DESIGN-V2 §5 alt. 6 and DECISIONS-ADDED A13 |
| V-17 | F-intv-02, F-storage-06 | CONFIRMED | "Most used"/"stays while read" contradicted the TTL semantics | **Recorded**: DESIGN-V2 §3 and README reworded (hot set = SKUs read within the last TTL; LFU is a backstop shared with the replay copies) |

## MINOR and NIT, with outcome

Fixed on `v2-fix-critique`: M-04 tombstoned row → 400 not 500 (test added); M-05 heap policy in the image; M-06 health names components; M-07 POST body cap 4 KB (Content-Length); M-09 replayed content validated against the row's CHECK set; M-12 (partly: charset consistent pending); M-23 store test at READ COMMITTED (done on v2-ops); M-31 double flush removed; M-32 stale comments; M-33 compose assertions; M-44 OQ3 comment; N-07 `isBetween`. Recorded (this file, DESIGN-V2, DECISIONS-ADDED, README): M-02 wording narrowed, M-03 (750 ms), M-08 growth and index note, M-11 (317ab0c pin stated), M-16 (FE17 in DEVIATIONS), M-21 (create/purchase race documented), M-22 (overlap note in the test), M-24 (clock skew assumption), M-35 to M-39, M-41, M-42, M-43, N-09, N-10. Fixed on `v2-frontend` 4a572d4: M-15, M-17, M-18, M-19. Fixed in PR #67: M-27, M-28 (staleness bound pinned with sleeps; refresh keeps the remaining TTL). Fixed in PR #68: M-14 (HEAD guarded like GET). Won't fix in this run: M-13 (guard before @Valid), M-20 (service OpenAPI `required`), M-25, M-26, M-29, M-30, M-34, N-02 to N-06, N-08, N-11, N-12, M-10 (CI; recorded deviation). Rejected: N-01 (trailing JSON tokens already return 400; the explicit `fail-on-trailing-tokens` setting stays as documentation).

## Re-check

See the "Re-check" section at the end of this file (filled after the fix branches were rebuilt).

## Re-check (integration 3769414 = v2-ops e169745 + v2-fix-critique cb2028b + v2-frontend 4a572d4, 21:50Z)

Service: `./gradlew build --warning-mode=fail` green, 666 tests in 36 classes, 24.8 s. Front end: lint, typecheck, Vitest 65/65, `check:api` clean, Playwright 12/12 (chromium + mobile-375, axe on three views). Against the rebuilt compose stack: `POST /%69nventory/RC-1;lot=7` → 400 (V-04); `Accept: application/json;q=0, */*;q=0.1` → 400 (V-05); `DELETE /inventory/x` with a bad form escape → 405 text/plain (V-06); a 5 KB POST body → 400 (M-07); `/actuator/health` names `db` and `redis` (M-06). V-01/V-02/V-12..V-15 are covered by the front-end suite's new tests and the e2e retry flow (12/12). Still open after PR #67: M-13, M-25, M-26, M-29, M-30, M-34, V-11's de-duplication (#61); M-14 (HEAD unguarded) is fixed in PR #68. PR #67 (670 tests green) adds the V-08, V-10, M-27 and M-28 tests; its first attempt stopped the shared Postgres container mid-suite because the second instance imported the test configuration and Boot's Testcontainers lifecycle stopped the containers on close; the instance is now configured by connection properties.
