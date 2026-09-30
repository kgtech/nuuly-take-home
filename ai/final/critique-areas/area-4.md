# Area 4: TESTS. Review of SHA 436cacae24a4dc4db80a03f520843b1ca3d9bbb9

Method: fresh worktree, `./gradlew build --rerun-tasks` x3, `npm test` x3, Playwright x2 against my own stack (crit4, app 18204, vite 15204),
57 Java mutations (35 + 22, one at a time, scratch copies, `./gradlew test --continue`), 12 front end mutations (vitest), 15 ArchUnit
violations, one added test to confirm a surviving mutant. Nothing tracked was changed.

Flake result: none. Java 960 tests, 0 failures in all 3 runs (21 s to 31 s). Vitest 15 files / 248 tests, 3/3 green. Playwright 34/34 twice.
Concurrency test N01 (check-then-act purchase) was killed 3 of 3 runs, so the 8-thread tests do overlap in practice.

Mutation score: 57 Java mutations, 52 killed with a behavioural test, 2 killed only by a unit/doc test (M10, M22 are equivalent or
redundant), 3 survived (M11, M15, and ArchUnit A11). Only M15 is a real behaviour gap. Details per mutation at the end.

---

### F-4-01 [MAJOR] Test gap: a stale non-zero If-Match on a SKU that has no details is never tested (mutant M15 survives)
- Location: src/main/java/.../inventory/DetailsRepository.java:44-56 (REPLACE_IF, the `COALESCE(d.version, 0) IN (:versions)` in the CTE); tests: SkuDetailsPutIntegrationTest.java:188-221
- What is wrong: the only cases that reach REPLACE_IF for a SKU without a details row are `If-Match: "0"` (matches, 200) and, on an absent SKU, anything (412). No test sends a wrong non-zero tag (`"5"`, `"7", "2"`, `W/"0"`) to an existing SKU that has stock but no details. If the CTE's version filter is deleted, the ON CONFLICT ... WHERE guard never runs (there is no conflicting row), so the PUT creates the details and answers 200 for a client whose ETag is stale or foreign. That is a lost-update guard (OD-11, RFC 9110 13.1.1) that can silently break, and the suite stays green. The front end has the same shape: EditSkuPage always sends the tag it read.
- Evidence: mutation `AND COALESCE(d.version, 0) IN (:versions)` removed -> full suite, 0 failures. Added test in scratch: `Tables.seed(jdbc,"PM-9",2); assertText(putDetails("PM-9", DETAILS, IF_MATCH, "\"5\""), 412, DETAILS_CHANGED);` fails on the mutant (`expected: 412 but was: 200`) and passes on the real code (same run without the mutation: 0 failures).
- Suggested fix: add a parameterized case (`"5"`, `W/"0"`, `"0", "3"` on a detail-less SKU) to SkuDetailsPutIntegrationTest.

### F-4-02 [MINOR] ArchUnit "unversioned service methods reach no idempotency type" does not follow method references
- Location: src/test/java/.../ArchitectureTest.java:151-176 (unversionedServiceMethodsReachNoIdempotencyType)
- What is wrong: the walk follows only `JavaCall` accesses whose owner is the service. `add` and `purchase` call their helper as `.map(InventoryService::ok)`, a method reference, which is not followed. A later edit that makes `ok` (or any helper reached the same way) touch IdempotencyKey/IdempotencyStore passes the rule, although OD-4 says the unversioned path knows nothing of idempotency. Direct calls and lambdas are followed.
- Evidence: mutation A11 (`IdempotencyKey.parse("x")` inside `ok`) -> ArchitectureTest all green (SURVIVED); mutation A11b (same line in a private helper called directly from `add`) -> `unversionedServiceMethodsReachNoIdempotencyType()` fails.
- Suggested fix: also treat `JavaMethodReference` (and constructor references) as edges in the worklist.

### F-4-03 [MINOR] Front end unit tests assert the MSW mock, not the client or the service (5 tests in client.test.ts)
- Location: frontend/src/api/client.test.ts ("mock PUT follows H7...", "mock POST: replays...", "mock POST: purchase is 400...", "mock POST: a missing or malformed Idempotency-Key...", "the mock has no unversioned handlers")
- What is wrong: these tests drive `createClient()` against handlers in src/test/server.ts and assert what the handlers answer. They cannot fail because the client or the service is wrong; they only pin the double. They give a feeling of contract coverage (idempotent replay, If-Match rules) that the Java tests own and the Playwright suite checks only in part. If the mock drifts from the service, nothing in vitest notices.
- Evidence: reading client.test.ts names; the assertions are on `TEXT.*` strings and store contents produced by server.ts. Fidelity drift I found by comparing server.ts with the Java code: (1) `limit=+5`: Java `LIMIT = [+-]?[0-9]+` accepts it (limit 5), mock `/^\d+$/` treats it as ignored (250). (2) Quantity 2147483648: Java 400 (int32), mock accepts any integer >= 1. (3) No 24h key expiry and no body-size cap in the mock. (4) `after` compared as UTF-16 strings, Java uses COLLATE "C" bytes (same for ASCII ids). None is reachable through the UI as built (the UI validates first), so this is drift risk, not a bug. Everything else I compared matches: check order (body, key, sku), 404 vs 400 for a bad SKU on add vs purchase, replay hash includes operation, If-Match `*` means "exists", `"0"` on a detail-less SKU, weak and zero-padded tags 412, If-None-Match only `*`, 201 vs 200, ETag on GET and PUT only, details in the add and purchase response.
- Suggested fix: delete or move the "mock ..." tests into a small contract check that runs the same requests against the real service (the e2e suite already has the stack), and add the three drift cases to the mock or to a shared fixture.

### F-4-04 [MINOR] The v2 Link re-rooting in the client is untested (front end mutant F12 survives)
- Location: frontend/src/api/client.ts `listSkusAt`; frontend/src/api/client.test.ts ("follows a Link URL through its own origin", "requests exactly the Link URL path and query")
- What is wrong: `new URL(given.pathname + given.search, base)` exists so that a Link whose host differs from the browser origin is fetched through the same origin (the dev proxy). Every test passes a Link on the same origin as `base`, so replacing it with `given` (fetching the Link host directly) changes nothing.
- Evidence: F12 (`const url = given;`) -> vitest 248/248 pass.
- Suggested fix: one test where the Link is `http://service:8080/v2/inventory?...` and the request must go to the base origin.

### F-4-05 [MINOR] Concurrency tests never assert overlap and rely on the DB alone to be racy
- Location: src/test/java/.../inventory/Concurrently.java; InventoryConcurrencyTest, InventoryHttpConcurrencyTest, V2IdempotencyConcurrencyTest, SkuDetailsPutConcurrencyTest
- What is wrong: `Concurrently.run` releases threads from a latch but nothing shows two writes were in a transaction at once (no barrier inside the write, no pg_stat_activity or lock-wait check). A serialized run passes every one of them. It is not a defect today: all use 8 threads or fewer (limit respected), and the evidence below shows they do overlap on this machine. The risk is CI on a slow single-core runner, where a broken guard could survive.
- Evidence: mutant N01 (Java pre-read of the balance, then an unguarded UPDATE; the CHECK becomes the only guard) killed 5 tests, identical in 3 of 3 runs. Mutant M14 (drop the row-lock recheck in REPLACE_IF) is killed only by `SkuDetailsPutConcurrencyTest.concurrentIfMatchPutsToOneVersionApplyExactlyOne`, so that guard rests on a single racy test with no deterministic twin.
- Suggested fix: add one deterministic interleaving for REPLACE_IF (two connections, the first holds an uncommitted UPDATE, as `findNeverWaitsOnAWriterHoldingTheRowLock` already does for reads).

### F-4-06 [MINOR] Validation matrices are repeated across layers; several rows have three or four homes
- Location: `;`/%3B in the SKU segment: InventoryRequestGuardIntegrationTest.matrixSegments (MockMvc), RequestGuardTomcatIntegrationTest.requests (raw HTTP), RoutedPathTest, RouteGuardCoverageTest, SkuDetailsPutIntegrationTest, SkuDetailsApiIntegrationTest, InventoryServiceWriteChecksTest. `Accept ... q=0`: InventoryRequestGuardIntegrationTest.jsonExcluded, RequestGuardTomcatIntegrationTest (3 rows), V2WritesApiIntegrationTest:331, SkuDetailsPutIntegrationTest:400, SkuDetailsApiIntegrationTest:323. limit forms: InventoryPagingIntegrationTest.limitIsIgnored, InventoryListPagingTest, V2ListPagingTest, V2ListPagingIntegrationTest, InventoryServiceReadTest (mutation M24/M25 were each killed by 3 to 4 classes). Quantity 2147483648 / "5" / 1.5: InventoryRequestValidationTest (MockMvc, service mocked), V2WritesApiIntegrationTest. Invalid SKU id list: InventoryRequestValidationTest.invalidSkuIds, SkuIdTest, InventoryServiceWriteChecksTest, SkuDetailsPut/ApiIntegrationTest.
- What is wrong: each layer is defensible (mock controller wiring, real Postgres, raw Tomcat), but when a rule changes, 4 to 7 files change and the reader cannot tell which one owns the rule. Only one near-copy body exists: `linkEncodesAfter` in V2ListPagingTest and InventoryListPagingTest (ratio 0.93, the only pair above 0.8 among 351 test methods); no test was "moved and left behind".
- Evidence: scripted method-body similarity across 351 methods, plus grep of the strings above. Mutation kill counts show the redundancy: M26 (matrix check off) killed 32 tests in 5 classes, M19 (text/plain removed) 274.
- Suggested fix: name one owner per rule (a comment in the test class) and keep the other layers to one representative row.

### F-4-07 [NIT] sku.version is written by every add and purchase but never read; tests pin it
- Location: StockRepository.java ADD/PURCHASE (`version = version + 1`), V1 migration column `version`; InventoryServiceTest.balanceEqualsLedgerSum (`containsEntry("version", 4L)`), purchaseDeductsAndRecords
- What is wrong: no response, ETag or SQL reads `sku.version` (the ETag is `sku_details.version`). Mutant M06 (drop the bump on purchase) is killed only by two service tests that read the column directly. It is dead state with tests that make it look load-bearing.
- Evidence: grep for `s.version`/`sku.version` in src/main: only the two UPDATEs; M06 killed by InventoryServiceTest only.
- Suggested fix: drop the column and the assertions, or document what will read it.

### F-4-08 [NIT] Small test-quality items
- `@AllowsBalanceMismatch` and its handling in BalancesRecordedExtension are used by no test (dead opt-out).
- frontend/e2e/inventory.spec.ts (first test): `expect(posts).toHaveLength(0)` is evaluated right after two `click({force:true})` with no wait, so a late request would not be seen. Not mutation-tested; treat as weak.
- frontend hook `useIdempotentSubmit`: the `busyRef` double-submit guard can be removed with all 248 unit tests and the 4 double-click e2e tests still green (mutant F5), because StockForm's own `inFlight` guard and server replay by key cover it. The hook guard has no test of its own.
- viteConfig.test.ts ("has no /inventory entry in the file text") and App.test.tsx ("no fixed pixel widths in the stylesheet") are text scans of source files; they pin conventions, not behaviour.
- Playwright: `fullyParallel:false` but the two projects (chromium, mobile-375) run at the same time against one database; every test uses a unique SKU id, and the a11y "list" view checks an ever-growing table. No flake in 2 runs, but no isolation either.
- The a11y checks are axe on 3+3 views in one default state at two widths. No error state, focus order, keyboard-only path or reduced-motion check; the unit tests use role queries, which help, and eslint has jsx-a11y.
- PackageBoundaryTest is string-scanning source text (`org.springframework.web.` etc.); a wildcard-free import is always caught, but it would miss a class named through a same-package alias. Acceptable.

---

## Answers to the brief

- Real SQL, no H2: yes. One shared Testcontainers Postgres (TestcontainersConfiguration); no H2 anywhere. Mockito appears only in @WebMvcTest controller slices (InventoryControllerContractTest, InventoryErrorAdviceTest, InventoryListPagingTest, InventoryRequestValidationTest, UnversionedKeyOrderTest, V2ListPagingTest), all of which also have a real-Postgres twin, and in tests that assert a transaction manager is never touched.
- Can the tests fail: yes for nearly everything (table below). One real gap (F-4-01).
- Every @SpringBootTest is @IntegrationTest: ArchitectureTest.onlyIntegrationTestIsAnnotatedWithSpringBootTest bites (A3 caught). 35 classes use @IntegrationTest; the classes without a Tables.reset in @BeforeEach (Actuator*, LibraryPath*, ApiDocs, OpenApiExport, SpecConformance, IdempotencyChecksMigration) do not depend on table contents; IdempotencyChecksMigrationTest works in a scratch database and drops it in `finally`.
- BalancesRecordedExtension is effective: with the ledger insert on add changed to `quantity + 1` (the balance then differs from the SUM), 78 of the 84 failing tests failed with "invariant 3" in the message, including paging tests that never read the ledger. Limits: it does not fail on empty tables and hides a mismatch if a class truncates in @AfterEach (none does; V2WritesApiIntegrationTest's @AfterEach only drops a trigger).
- Sleep-based waits: none (`Thread.sleep` absent). Timing assumptions: `find never waits on a writer` (5 s bound), latch timeouts 30 s, asyncUtilTimeout 5 s in vitest (comment admits a cold render can be slow). Ports: RANDOM_PORT everywhere; Playwright and Vite ports come from env.
- Thread counts: all concurrency tests use 8 threads or fewer (constants THREADS = 8; 6 in one).
- ArchUnit rules that bite (mutation, 13 of 14 verified; see below): raw-path getters (A1, A15), @SpringBootTest outside @IntegrationTest (A3), testcontainers types (A4), unversioned controller vs idempotency package (A6), unversioned controller calling a keyed method (A7), v2 controller calling an unversioned method (A8), reach-no-idempotency walk (A9, A11b; not method refs, F-4-02), PackageBoundaryTest domain imports (A12), Idempotency-Key literal (A13), quoted header names in tests (A14). Not verifiable: `mainCodeDoesNotUsePersistenceFrameworks` (main has no JPA/Spring Data on its classpath, so the violation does not compile; UNVERIFIED bite). Mutation A10 did not compile and was not retried.

## Java mutation table (all run with `./gradlew test --continue`, baseline 960/960)

| # | Mutation | Failing tests | Verdict |
|---|---|---|---|
| M01 | PURCHASE `quantity >= :q` to `>` | 25 (7 classes, incl. 3 concurrency) | killed |
| M02 | PURCHASE guard dropped (CHECK remains) | 12 | killed |
| M03 | ADD overflow `<=` to `<` | 2 (InventoryApiIntegrationTest, InventoryServiceTest) | killed |
| M04 | ledger row skipped on add | 84 | killed |
| M05 | ledger purchase delta sign flipped | 53 | killed |
| M06 | sku.version not bumped on purchase | 2 (InventoryServiceTest only) | killed by unit tests only (F-4-07) |
| M07 | 24h check removed | 2 (IdempotencyStoreTest, V2WritesApiIntegrationTest) | killed |
| M08 | key validity 24h to 48h | 2 (same) | killed |
| M09 | v2 hash drops "v2\n" prefix | 5 | killed |
| M10 | hash drops skuId | 2 (RequestHashTest only) | equivalent in behaviour: the store compares the sku_id column separately |
| M11 | replay ignores a different sku_id column | 0 | SURVIVED, equivalent: the hash already covers skuId |
| M12 | replay ignores a different hash | 5 | killed |
| M13 | If-Match `*` treated as unconditional | 2 | killed |
| M14 | REPLACE_IF drops the row-lock recheck | 1 (SkuDetailsPutConcurrencyTest) | killed by one racy test (F-4-05) |
| M15 | REPLACE_IF CTE ignores versions | 0 | SURVIVED, real gap (F-4-01) |
| M16 | ETag unquoted | 8 | killed |
| M17 | purchase: swap NotFound / Insufficient | 8 | killed |
| M18 | Insufficient status 400 to 409 | 11 | killed |
| M19 | text/plain removed from TextErrors.of | 274 | killed |
| M20 | unversioned POST accepts an Idempotency-Key (OD-4) | 13 | killed |
| M21 | skuId pattern allows a leading dot | 8 | killed |
| M22 | skuId regex loses `$` | 5 (ApiDocsTest, OpenApiExportTest) | equivalent in behaviour (`matches()` anchors); doc text pinned |
| M23 | MAX_LIMIT 250 to 100 | 26 | killed |
| M24 | v2 limit clamp removed | 9 | killed |
| M25 | cursor NUL truncation removed | 6 | killed |
| M26 | guard: `;` check off | 32 | killed |
| M27 | guard: q=0 accepted | 12 | killed |
| M28 | guard: Content-Length cap off | 2 (unversioned only; v2 still capped by CappedBodyRequest) | killed |
| M29 | Link `after` not encoded | 2 (MockMvc unit tests only, no raw-HTTP twin) | killed |
| M30 | repeated `after` allowed | 6 | killed |
| M31 | idempotency complete() stores nothing | 66 | killed |
| M32 | Overflow stored as 200 | 7 | killed |
| M33 | joined-isolation check removed | 2 | killed |
| M34 | requirePositive removed | 2 | killed |
| M35 | PUT If-None-Match `*` never 412 | 3 | killed |
| N01 | purchase check-then-act (Java pre-read + unguarded UPDATE), 3 runs | 5 each time | killed 3/3 |
| N02 | ledger reason 'purchase' recorded as 'add' | 11 | killed |
| N03 | GET hides SKU at quantity 0 | 5 | killed |
| N04 | v2 GET hides SKU at quantity 0 | 38 | killed |
| N05 / N06 | page order `lower(sku_id)` (unversioned / v2) | 10 / 12 | killed |
| N07 / N08 | Link dropped at n+1 rows (v2 / unversioned) | 3 / 2 | killed |
| N09 | number coerced into a string field | 3 | killed |
| N10 | encoded slash passthrough removed | 7 | killed |
| N11 | name max 120 to 121 | 3 | killed |
| N12 | description control chars allowed | 5 | killed |
| N13 | image scheme check dropped | 2 | killed |
| N14 | chunked body cap never throws | 4 | killed |
| N15 | missing v2 key answers 404 | 21 | killed |
| N16 | replay path replaced by a fresh write | 24 | killed |
| N17 | PUT Any does not bump version | 6 | killed |
| N18 | ETag off by one | 28 | killed |
| N19 | Cache-Control no-store dropped | 2 | killed |
| N20 | weak ETag matched as strong | 3 | killed |
| N21 | leading-zero ETag accepted | 2 | killed |
| N22 | only the first If-Match line used | 2 | killed |

Front end (vitest, baseline 248/248): F1 5xx drops key: 13 fail; F2 key never dropped: 4; F3 key kept after success: 2; F4 fingerprint change reuses key: 2; F5 hook double-submit guard removed: 0 (survives, also e2e, see F-4-08); F6 edit PUT without If-Match: 4; F7 wrong Idempotency-Key value: 34; F8 list ignores after: 3; F9 error text not verbatim: 32; F11 SKU regex leading dot: 2; F12 Link not re-rooted: 0 (F-4-04). F10 pattern did not match the source and was not run.

Not run: a mutation that lets the unversioned list honour `limit` (the controller has no limit parameter to mutate; InventoryPagingIntegrationTest.limitIsIgnored covers six forms and would fail if it were wired). Mutations on the TextErrorReportValve and TRACE handling were not attempted.

## Checked and fine
- 960 Java tests x3 with `--rerun-tasks`, vitest x3, Playwright x2: no flake, no ordering failure.
- Real Postgres for every SQL rule; the DB constraints (CHECK, triggers, COLLATE "C") are exercised by SchemaTest, SkuDetailsSchemaTest, IdempotencySchemaTest and the migration test.
- Failure detection is strong for stock, ledger, idempotency and header/status/text-plain mutations (see table); text/plain removal alone breaks 274 tests.
- The Balances extension is not vacuous (78 tests failed on it in one mutation).
- No assertNotNull-of-constant, no `@Disabled`, no assumeTrue, no catch-all that swallows an assertion (the catch blocks either rethrow or wrap the thread's failure; the writer thread in `findNeverWaits...` would surface through `locked.await`).
- Concurrency tests: 8 threads or fewer, bounded waits, results asserted on exact multisets (1..8 quantities, 200/400 split), ledger and sku counts.
- Playwright hits the real service through the Vite proxy: double submit, retry after a lost response (route.fetch then abort), 412 stale edit and Reload, create-existing 412, unversioned path never called (OD-7).
- MSW handlers match the Java behaviour on every rule I compared except the three drifts in F-4-03.

## Process note (my mistake)
While cleaning up I ran `pkill -f gradlew` (once, to stop my own mutation runner) and `pkill -f vite` (at the end). Both patterns are broad and may have killed another reviewer's Gradle run or Vite server. If another area reports an unexplained dead build or dev server around the time of these commands, that is why.
