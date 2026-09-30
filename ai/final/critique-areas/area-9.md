# Area 9: Simplification (SHA 436cacae24a4dc4db80a03f520843b1ca3d9bbb9)

## Measurements (main and test code, `git ls-files`, ai/ excluded unless said)
- src/main: 2,735 lines in 49 Java files (+ 4 migrations, 1 yaml). Largest: SkuDetailsController 244, InventoryService 234, InventoryController 169, InventoryRequestGuardFilter 149, IdempotencyStore 148, DetailsRepository 128.
- src/test: 64 files, 10,361 lines (3.8x main). Largest: ApiDocsTest 541, V2WritesApiIntegrationTest 540, SkuDetailsPutIntegrationTest 423, InventoryPagingIntegrationTest 374, IdempotencyStoreTest 372.
- frontend (no lockfile, no generated schema.d.ts): about 5,100 lines incl. tests and css; a scan for exported symbols with no non-test user found none, so the front end has no dead exports.
- ai/: 1.41 MB tracked. ai/decision-board.html (568 KB) is not deletable: ai/export-board.mjs runs its own genDecisions/genClaude to regenerate DECISIONS.md and CLAUDE.md (OD-9). ai/final/board-db is 420 KB of the same board's rows (OD-10). Left alone.
- Method note: every build result below is `./gradlew build` (compile, all Testcontainers tests, ArchUnit, ApiDocs/export/conformance tests) on a scratch worktree of the SHA. No front-end file changed in any of the three, so the front-end gate was not re-run (`check:api` inputs are untouched). Patches are saved in /private/tmp/claude-501/critique/area9-patches/E-a.patch, E-b.patch, E-c.patch (apply on the SHA).

Ranking (lines deleted, then risk). All three proven experiments are behaviour-neutral: same hashes, same SQL results, same HTTP bytes.

### F-9-01 [MINOR] The unversioned read path duplicates the /v2 read path (StockRepository.find/page, second page-slicing loop, two page types of the same shape)
- Location: src/main/java/com/kgtech/inventoryapi/inventory/StockRepository.java (FIND, PAGE, `find`, `page`); InventoryService.java `find` and `list` (about lines 170-195); InventoryServiceReadTest.java unversioned block.
- What is wrong: GET /inventory/{skuId} and GET /inventory are the /v2 reads (`details.find`, `listSkus(null, after)`) minus the details. The service carries a second SQL (`SELECT ... FROM sku` twice), a second keyset loop with its own NUL-truncation/limit+1 slicing (`list` vs `listSkus`), and a second set of unit tests for the same slicing (NUL cut, extra-row cursor). An interviewer asking "why are there two list implementations that must stay in step for invariant 6 (version parity)?" gets no answer but history. One implementation makes parity true by construction.
- Evidence (done in scratch, E-b.patch): `find` = `details.find(skuId).map(InventoryService::item)`; `list(after)` = `listSkus(null, after)` mapped to InventoryItem; StockRepository.FIND/PAGE/find/page deleted. `./gradlew build`: BUILD SUCCESSFUL, 955 tests, 0 failures (includes InventoryPagingIntegrationTest 250/251 walk, VersionParityIntegrationTest, SpecConformanceTest, ApiDocsTest, the "locked row" read in InventoryServiceTest that proves reads do not wait on a writer). Diff with F-9-02 and F-9-03's toResponse move: main +35/-84, test +4/-37 (net -82 lines, 6 files).
- Does not break: invariants 3, 6, 7 (same query for both, 250 cap in one place); the spec (response shape is still {skuId, quantity} via InventoryItem); OD-5 (list still ignores limit: `listSkus(null, after)`). Cost: the unversioned GET now does a LEFT JOIN on sku_details by primary key (250 rows per page), which CLAUDE.md rule E1 ("unversioned reads join nothing") states otherwise; E1 must be changed on the board first (the rule was a performance note, not a contract).
- Test that proves nothing is lost: existing VersionParityIntegrationTest, InventoryPagingIntegrationTest, SpecConformanceTest; the unversioned NUL and cursor unit tests are duplicates of `listSkus*` tests that stay.
- Suggested fix: apply E-b.patch's `find`/`list` change and delete the StockRepository read statements.

### F-9-02 [MINOR] Both controllers carry an identical `toResponse` switch; both service families repeat the add/purchase branch logic
- Location: InventoryController.java (last method, about lines 160-169) and SkuDetailsController.java (last method, about lines 230-244); InventoryService.java `add`/`addV2`, `purchase`/`purchaseV2`.
- What is wrong: the 8-line `switch (result)` mapping Stored/InvalidRequest/outcome to a ResponseEntity is copied verbatim (same javadoc idea) into two classes and both import five outcome types for it. In the service, `add` and `addV2` differ only in how an `Ok` is built (with or without details, which must stay separate: putting details on the unversioned Ok would leak an extension onto a spec path); the `stock.add(...).map(...).orElseGet(Overflow)` and purchase equivalents (with the `exists` check that separates 404 from 400) are written twice.
- Evidence (E-b.patch): `OutcomeResponses.toResponse(skuId, result)` used by both controllers (they already inject OutcomeResponses); `addStock`/`purchaseStock` private helpers taking a `Function<Balance, WriteResult>` for the Ok. Build green as in F-9-01 (same experiment). Lines: about -20 in controllers/service net.
- Does not break: G6/S5/Y4 (same StoredResponses path), invariant 5 (unversioned Ok still has no details; ArchitectureTest still passes).
- Test: existing InventoryControllerContractTest, V2WritesApiIntegrationTest, UnversionedIdempotencyIntegrationTest.
- Suggested fix: as above.

### F-9-03 [MINOR] IdempotencyStore has dead "join a caller's transaction" logic and an API-version enum for a version that never reaches it
- Location: src/main/java/com/kgtech/inventoryapi/idempotency/IdempotencyStore.java (the `joined` isolation check and its javadoc, about lines 84-92; the 5-argument `run` overload, lines 72-76); ApiVersion.java (whole file); RequestHash.java (`of` versus `ofV2`); InventoryService `keyed(ApiVersion, ...)` parameter; IdempotencyStoreTest lines about 268-350 (4 tests) and RequestHashTest's unversioned pins.
- What is wrong: (a) The only caller, InventoryService.keyed, calls `idempotency.run` outside any transaction (the test `verifyNoInteractions(transactions)` says so) and the controllers are not transactional, so the branch "joins a READ COMMITTED or DEFAULT caller, refuses REPEATABLE READ/SERIALIZABLE with IllegalStateException" can never run in production; it exists for a caller that does not exist, and four Testcontainers tests exist only for it. (b) The unversioned API never touches the idempotency table (OD-4, invariant 5), so `ApiVersion.UNVERSIONED`, the 5-argument `run`, and `RequestHash.of` (unprefixed hash) have no production caller; only the test helper in IdempotencyStoreTest uses them. `ApiVersion.V2` is passed by both call sites, always.
- Evidence (scratch, two separate experiments):
  - E-a.patch (version leftovers): delete ApiVersion.java, the 5-arg overload, the `version` parameter, the old `RequestHash.of`; rename `ofV2` to `of` and KEEP the "v2\n" prefix so every stored row and every legacy build-v2 row keeps its hash (V2WritesApiIntegrationTest.aKeyStoredByAnUnversionedRequestNeverReplaysOnV2, which seeds an old-style hash, still passes unchanged). `./gradlew build`: BUILD SUCCESSFUL, 959 tests, 0 failures. main +6/-18, test +8/-29, net -33 lines, 5 files plus one file deleted.
  - E-c.patch (dead join logic): remove the `TransactionSynchronizationManager` check, its import and the four join/refuse tests. `./gradlew build`: BUILD SUCCESSFUL, 955 tests (four fewer), 0 failures. main +4/-14, test -87, net -97 lines, 2 files.
- Does not break: OD-3/OD-4, invariant 2 (V2IdempotencyConcurrencyTest, "two concurrent requests with the same key" still green), invariant 5, T1 expiry (still uses the store's own transaction clock).
- What must change first: CLAUDE.md rule A33 (the sentences "or joins a READ COMMITTED or DEFAULT caller's transaction and refuses any other isolation..." and its Test line) and H10's "a key stored by an unversioned request" wording are generated from the board; the board cards A33/H10 need editing (S9), not the exports.
- Trade-off to state honestly: the deleted guard is a tripwire for a future caller that wraps the store in a SERIALIZABLE transaction. Nobody does today; if the interviewer values the tripwire, keep it and delete only (b).
- Suggested fix: apply E-a (zero risk) and E-c (low risk, needs the A33 card edit).

### F-9-04 [MINOR] `sku.version` and `Balance.version` are written on every stock change and never read
- Location: V1__inventory.sql (`version bigint NOT NULL DEFAULT 0`); StockRepository.ADD/PURCHASE (`version = version + 1 ... RETURNING quantity, version`); Balance.java (`record Balance(long quantity, long version)`); test helper Tables.java line 49-50.
- What is wrong: the ETag and If-Match use `sku_details.version`; the stock row's version is left over from an earlier design (DESIGN-V2 §1 "the version that changes with it"). `grep '\.version()' src/main` finds no reader, and no test asserts it. A write-only column is one extra field in three SQL statements, a record component, and an unanswerable "what reads this?".
- Evidence: `grep -rn "version" src/main/java/.../StockRepository.java Balance.java` shows only writes; no SELECT of `s.version` (DetailsRepository.ITEM selects `d.version` from sku_details). UNVERIFIED by build (not attempted: it needs a V5 migration to drop the column or an edit of applied V1, which the migration rules (D5) forbid for a deployed DB).
- Suggested fix: drop `version` from the ADD/PURCHASE statements and Balance; drop the column in a new migration. About -8 lines plus one migration; not worth doing unless F-9-01 is taken.

### F-9-05 [NIT] Dead sealed sub-interfaces in StockOutcome
- Location: src/main/java/com/kgtech/inventoryapi/inventory/StockOutcome.java lines 8-12 (`Add`, `Purchase`) and the `implements Add, Purchase` clauses.
- What is wrong: `Add` and `Purchase` are referenced by no other file (`grep -rn "StockOutcome.Add\|StockOutcome.Purchase\|implements Add"` finds only StockOutcome.java). They are leftovers of the removed generic result strategy. About 6 lines.
- Evidence: the grep above. Not built.
- Suggested fix: delete both interfaces; records implement `StockOutcome` directly.

### F-9-06 [NIT] The two list Link builders are tested twice
- Location: web/InventoryListPagingTest.java (linkHeaderFormat, linkReflectsRequestHost, linkEncodesAfter, linkDropsPathParameters, linkKeepsContextPath, linkCarriesOnlyAfter, about 100 lines) and web/V2ListPagingTest.java (linkHeaderCarriesLimitAndAfter, linkUsesNormalizedLimit, linkDropsOtherQueryParams, linkEncodesAfter, about 60 lines).
- What is wrong: both run through `Paging.link`, one function; host, context-path, encoding and dropped-parameter behaviour are asserted in each. Keep the unversioned set (it has the context-path and host cases) and keep in the v2 file only the two limit-specific tests. Not built; estimated -40 lines, no invariant involved. UNVERIFIED on build; overlaps with area 4.
- Also: `Paging.nextLink` has two public overloads that both call one private `link(basePath, Integer limit, after)`; callers could call it with `null` limit. -6 lines.

### DECISION CHALLENGE (report only, no experiment)
### F-9-07 [DECISION CHALLENGE] The v2 request hash prefix "v2\n" (H10) only protects rows the current code can no longer create
- What: after OD-4 the unversioned API stores no keys, so H10's purpose (a key stored by one version must not replay on the other) is met by the table simply never holding unversioned rows in a fresh database. The prefix and its test exist only for rows written by build v2 before OD-3/OD-4 moved keys to /v2. If final ships with a fresh schema (compose up, Testcontainers), H10 plus `V2WritesApiIntegrationTest.aKeyStoredByAnUnversionedRequestNeverReplaysOnV2` could go, giving a plain SHA-256 of operation, skuId, quantity again (Y3). Small: about -15 lines. Recorded as a challenge because it changes a board decision, and because dropping the prefix would make any pre-existing build-v2 row replay on /v2 (a behaviour change for a migrated database). Not recommended for a reviewed submission.

## Candidates examined and rejected (do not delete)
- InventoryRequestGuardFilter/RoutedPath/RouteKind: RoutedPath is the only place the ";" and %3B SKU rule is decided (C3) and RouteGuardCoverageTest ties the rule table to Spring's route set; the PROMPT freezes this hardening and no check is a pure duplicate of another. `RouteKind.LIST/OTHER` map to null in `rule()`, but the coverage test needs them to name the shapes.
- SkuId.isValid is called in six service methods, not in the filter or the controllers (the filter checks only ";"), so there is no triple validation to remove; each call precedes I/O for a different operation.
- OutcomeResponses/KeyedResponses/StoredResponses: one implementation of KeyedResponses looks like a one-implementation interface, but ArchitectureTest/PackageBoundaryTest require the domain package not to import HTTP types (Z2); the interface is what keeps that rule true.
- The ledger: invariant 3 requires it and its append-only trigger.
- openapi.yaml and openapi-v2.yaml: required by the spec-conformance and export tests (D7, PROMPT "Target API").
- Front end: no dead exports; hooks (`useHashRoute` 55 lines, `useIdempotentSubmit` 68) each have a caller and the e2e for key reuse depends on `useIdempotentSubmit`.
- ai/decision-board.html and ai/final/board-db: input to the board exporter (OD-9, OD-10).

## Totals for the three proven experiments if all are applied
E-a (-33) + E-b (-82, includes F-9-01 and F-9-02) + E-c (-97) is about -210 lines net (main -116 raw deletions, tests -153), 12 files touched. The three patches touch disjoint code except InventoryService.java between E-a and E-b (E-a edits `keyed(...)`, E-b edits `add/purchase/find/list`); they merge with a trivial hand fix, not re-built together.

## Checked and fine
- Every SKU/Idempotency-Key/Accept check I found is either unique to its layer or frozen hardening.
- The two service method families cannot be merged wholesale because an unversioned Ok must not carry details (invariant "no extension leaks"); only their bodies can share helpers (F-9-02).
- Paging vs UriComponentsBuilder: `Paging.link` already is a thin UriComponentsBuilder wrapper (ServletUriComponentsBuilder), nothing to replace.
- StockOutcome.Ok carrying Optional details is the mechanism for the /v2 details in the response and stored replay bytes; a simpler shape would need two Ok records and two render paths.
