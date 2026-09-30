# Re-check of the fixed findings (origin/final 6b74a3d, PRs #117 and #118)

Own stack: compose project critr (API 18211), Vite 15211; scratch copy mut-rc for mutants. Probes in /private/tmp/claude-501/critique/rc/.

| ID | Original evidence | Result now | Still open? |
|---|---|---|---|
| M-01 | chunked 8 MB POST /inventory/CH-1 -> 200; 30 x 19 MB chunked -> 200, RSS 367 MiB -> 3.16 GiB | 8 MB chunked -> 400 text/plain "Invalid request" (same as /v2). 30 x 19 MB chunked: no 200 (server closes early, clients see 400/reset), AMP-1 never created; app 290 -> 283 MiB, limit 768 MiB (mem_limit 805306368), no OOM/restart. Chunked 4096 B -> 200, 4097/5000 -> 400, small chunked -> 200. | fixed (see N-1 for stale docs) |
| M-02 | GET/POST /error -> 500 JSON/HTML; bad chunk size -> 400 application/json | GET, POST, PUT, DELETE /error and Accept text/html -> 404 text/plain "Not Found". Chunk size `zz` on POST /inventory/A1, /purchase, /v2 add, /v2 purchase, PUT details -> 400 text/plain "Invalid request". | fixed |
| M-03 | 1027 px (1280) / 1780 px (375) overflow on SKU page; 217 px on Edit with 64-char id | Own Playwright probe (118-char name, 64-char id; SKU, Edit, list, not-found, new pages at 375 and 1280): overflow 0 everywhere. The repo's e2e cases for Create 412, Add/Purchase success, not-found pass in both projects (48/48 e2e green). | fixed |
| M-04 | predicate removed, suite green | Predicate `AND COALESCE(d.version, 0) IN (:versions)` removed in scratch: SkuDetailsPutIntegrationTest 58 run, 3 fail (`aStaleIfMatchOnASkuWithStockAndNoDetailsIs412...` for "5", "7, 2", "2"). | fixed |
| M-11 | {"quantity":1,"quantity":2} -> 200, adds 2 | 400 text/plain on unversioned add and purchase, /v2 add and purchase, and details PUT (duplicate name); stock unchanged. | fixed |
| M-12 (FE) | Create 412 showed jargon headline | Live Create over existing SKU: alert is "A SKU with this ID already exists. Open it to edit its details or add stock." plus the Open link; no server text. | fixed |
| M-13 | Edit 5xx said "Sending again is safe" | Live (503 faked on the PUT): "Service Unavailable / The save may or may not have been applied. Reload to see the current details." | fixed |
| M-14 | 30 s pool wait, no lock timeout | application.yaml sets connection-timeout 3000 and lock_timeout 5000. Row lock held by psql: blocked POST -> 500 text/plain "Internal server error" at 5.06 s. With 12 blocked purchases (pool of 10 exhausted), an unrelated GET waited 3.0 s and got 500, later unrelated POSTs/GETs and liveness answered 200 in under 1 s; nothing hung for 30 s; service recovered on lock release. | fixed (residual: while the pool is exhausted, unrelated requests can 500 after 3 s, by design) |
| M-22 | ArchUnit ignored method references | Scratch mutant: `find` gets `Supplier<Object> r = InventoryService::leak` where `leak()` returns idempotency.Operation values: ArchitectureTest.unversionedServiceMethodsReachNoIdempotencyType fails (1 of 8). | fixed |
| M-24 | Link re-root mutant survived | Scratch mutant `const url = given;` in client.listSkusAt: vitest "re-roots a Link URL from another origin onto the base origin (M-24)" fails (1 of 19). | fixed |
| M-38 | dead Add/Purchase interfaces | StockOutcome now has only records Ok, NotFound, Insufficient, Overflow. | fixed |
| M-40 | `#/sku/x/edit/` parsed as SKU `x/edit/` | Live: `#/sku/x/edit/`, `#/sku/x/`, `#/sku/a%2Fb` -> "Page not found"; `#/sku/x/edit` still the Edit page. | fixed |

Regression sweep
- README "Try it" (unversioned and /v2, details, paging) on a fresh database with the port swapped: every status, header and body matched the comments.
- Four spec operations (add 200, get 200, purchase 200/400 Insufficient, unknown SKU 404, list 200): status, Content-Type and bodies as specified.
- `./gradlew build`: BUILD SUCCESSFUL (exit 0). Front end: lint, typecheck, check:api, vitest (15 files, 257 tests), build all green; Playwright 48/48 green against the running stack.
- App log: 0 ERROR lines on the fresh run.

New problems
- N-1 (LOW, docs): CLAUDE.md [A19] (line 85) and README line 223 still say "Unversioned chunked bodies stay uncapped", and README 197 says the cap counts chunked bodies only "on /v2". The code now caps them (M-01). The board card A19 and generated CLAUDE.md need the reword, README 197 and 223 too.
- N-2 (INFO): for very large in-flight chunked bodies the server closes early, so some clients see a connection reset instead of the 400 (the 8 MB case still gets a clean 400). Normal for Tomcat, not a defect.
- N-3 (INFO): M-39's dead `@AllowsBalanceMismatch` annotation is still present (README line about it says "none does today"); it was not in the re-check list.

Totals: 12 of 12 re-checked findings fixed, 0 still open; 1 low docs problem new.
