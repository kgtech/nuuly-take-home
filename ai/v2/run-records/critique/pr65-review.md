# Review of PR #65 (ops) — review 5332141900 — findings
- F-01 MAJOR — InventoryRequestGuardFilter scopes on the raw getRequestURI(); Spring routes on the decoded, matrix-stripped RequestPath. `POST /%69nventory/ABC-1;lot=7` and `POST /inventory;v=1/ABC-1;x/purchase` bypass the filter and hit ABC-1. Fix: RequestPath.parse(...).pathWithinApplication(), check the SKU PathSegment's raw value/parameters; add encoded/matrix-prefix rows through real Tomcat.
- F-02 MAJOR — acceptsJson accepts if any JSON-compatible range has q>0; RFC 9110 §12.5.1 / C3: the most specific range decides, so `application/json;q=0, */*;q=0.1` is a JSON refusal; the test pins the wrong behaviour.
- F-03 BLOCKER (by rubric, #56 AC3) — backdating by "24 hours 1 second" in a separate transaction makes `<` vs `<=` indistinguishable. Fix: set created_at = now() - interval '24 hours' and execute in the same transaction (now() constant per tx); assert Replayed (fails under `<=`).
- F-04 MINOR — filter emits text/plain;charset=UTF-8 while TextErrors responses carry text/plain without charset (test asserts startsWith).
- F-05 MINOR — filter answers before @Valid/key checks: `POST /inventory/ABC-1;x/purchase` with a bad body gives 404 not U3's 400.
- F-06 MINOR — HEAD unguarded (HEAD /inventory/ABC-1;x → 200); `purchase;x=y` literal segment passes.
- F-07 MINOR — acceptsJson reads only the first Accept header.
- F-08 MINOR — @IntegrationTest keeps webEnvironment as an attribute, @AutoConfigureMockMvc per class: 6 contexts (C-21 target 4); properties alias unused.
- F-09 MINOR — PR-body claims: #54 AC4 rows (`/inventory;v=1/x`, `/%69nventory/x`) missing in JsonAcceptForGetFilterTest; Operation.java cites "plan OQ3"; #60 body lacks runtime per layer/mutation scores; smoke run unrecorded.
- F-10 MINOR — LedgerFaultTrigger Javadoc mentions the removed txid sequence; double blank lines.
- F-11 NIT — healthcheck greps `"status":"UP"` in the body; grep the status line instead.
- F-12 NIT — ci-workflow front-end job hard-fails without frontend/ (only matters before #62 merges).
Fix before merge: F-01, F-02, F-03.
