# Critique, merged and verified (SHA 436cacae24a4dc4db80a03f520843b1ca3d9bbb9)

Verifier: own stack (compose project critv, API 18210, Vite 15210), raw-socket and curl probes, Playwright at 375 and 1280 px, one scratch mutation run of the Java suite (copy of the SHA in /private/tmp/claude-501/critique/mut, nothing tracked changed). Scratch probes: /private/tmp/claude-501/critique/sv/.
Status vocabulary: CONFIRMED = I reproduced it (or, for record findings, read the cited files and the gap exists and is fair); PLAUSIBLE = real by the reviewer's evidence and my reading, I did not re-run it; REJECTED.
Severity shown is mine; "(src X)" notes a source that rated it differently.

## Summary

| Severity | Count |
|---|---|
| BLOCKER | 0 |
| MAJOR | 4 |
| MINOR | 30 |
| NIT | 8 |
| Rejected | 1 |
| Total merged | 43 (from 9 areas, about 100 source findings) |

Status: CONFIRMED 26, PLAUSIBLE 16, REJECTED 1.
DECISION CHALLENGE (not fixable by us, record only): M-05, M-06, M-12 (service text), M-21, M-28, M-29.
Source MAJORs I downgraded: F-1-01 (ETag), F-7-01, F-7-02, F-7-03, F-7-04, F-7-05 (all to MINOR, reasons in each entry). F-7-06 stays MAJOR (merged into M-01), F-3-01, F-4-01, F-5-01 stay MAJOR.
Spot-checked cheap MINORs: M-11/F-3-04, M-13/F-5-03, M-16/F-3-03, M-17/F-8-04, M-30/F-7-11, M-33 (ports), M-34/F-6-08, M-38/F-9-05, M-15/F-1-02, M-21/F-1-08 (all CONFIRMED), and F-3-06 (REJECTED).

## Merged findings

### MAJOR

**M-01 [MAJOR, CONFIRMED] Unversioned chunked POST bodies have no size cap; unauthenticated memory exhaustion; A19 card and README disagree**
- Sources: F-3-05, F-6-02, F-6-03, F-7-06 (MAJOR), F-1-06.
- Location: InventoryRequestGuardFilter (Content-Length cap only; "Frozen (A19)"); CappedBodyRequest applies to /v2 only; compose.override.yaml (no mem_limit; MaxRAMPercentage=75 of host).
- Evidence (mine): chunked POST /inventory/CH-1 with an 8 MB body: 200, stock changed. Same body on /v2 with a key: 400 text/plain. Content-Length 4114 body: 400. 30 concurrent chunked 19 MB bodies (an ignored string just under Jackson's limit): all 30 answered 200, quantity 30, and the app container RSS went from 367 MiB to 3.16 GiB and stayed there (scripts sv/raw.py, sv/amp.py). Also DECISIONS A19 says "4 KB on both POSTs"; README 197 says 4 KB, README 223 says chunked uncapped. F-1-06: a huge single string is stopped by Jackson at 20 MB (400), so README's "uncapped" wording is loose.
- Disposition: fix, because it rates MAJOR (security/DoS) and so is allowed to thaw the frozen A19 behaviour. Smallest fix: wrap the unversioned writes in the existing CappedBodyRequest in the guard filter (cap 4096 counted while read), one raw-HTTP test (5 KB chunked: 400), and `mem_limit: 768m` on the app service in compose.override.yaml. Then reword A19 (board card, regenerate) and README 223. Needs the owner's nod because PROMPT froze it; if the owner declines, record the risk under "Designed, not built".

**M-02 [MAJOR, CONFIRMED] A direct request to /error returns 500 with Boot's JSON or HTML Whitelabel body; malformed chunk framing gets the same JSON body**
- Sources: F-3-01 (MAJOR), F-3-02 (MINOR).
- Location: Boot's BasicErrorController is reachable at the literal path /error, and Tomcat's ErrorPage dispatch renders JSON when a body read fails inside Tomcat; InventoryErrorAdvice does not answer either.
- Evidence (mine): `GET /error` -> 500, application/json, `{"status":999,"error":"None"}`; with `Accept: text/html` -> 500 text/html Whitelabel; `POST /error` -> 500 JSON; `/error/` -> 404 text/plain (correct). Raw socket: chunked POST /inventory/A1 with chunk size `zz` -> 400 `application/json` `{"timestamp":...,"status":400,"error":"Bad Request","path":"/inventory/A1"}`.
- Why MAJOR: a client can make the server answer 5xx with a non-text/plain body on a path under the standard-code rule (G10, S5, T3, C1); 500 is only allowed for server faults.
- Disposition: fix. One root cause: Boot's /error view. Smallest fix: answer a direct /error hit (no forwarded error attributes) with 404 text/plain "Not Found" and render the ErrorPage dispatch through TextErrors (or point `server.error.path` at an unroutable path and use the C1 valve for the body-read failure); tests through RANDOM_PORT raw HTTP for `/error` (all methods) and the bad chunk size on the four write routes.

**M-03 [MAJOR, CONFIRMED] A long unbroken name or SKU id makes the SKU and Edit pages scroll sideways (desktop and 375 px)**
- Source: F-5-01.
- Location: frontend/src/styles.css h2 (no overflow-wrap; only h1 has it), .kicker; SkuView.tsx `<h2>{details.name}</h2>`.
- Evidence (mine): PUT details with a 118-char name without spaces; `#/sku/LONG-1` overflow (scrollWidth minus innerWidth) 1027 at 1280 px and 1780 at 375 px. Edit page for a 64-char SKU id at 375 px: 217 (list page and 1280 px Edit: 0). Breaks the stated FE18/FE22 "no horizontal overflow at 375 px". Likelihood is low (legal input, not typical), so this is the weakest MAJOR; keep it because it violates a stated invariant and is a five-minute fix.
- Disposition: fix (front end): `overflow-wrap: anywhere` on `h2, .kicker` (and h3), add a long-name and 64-char-id case to the Playwright overflow test.

**M-04 [MAJOR, CONFIRMED] No test sends a stale non-zero If-Match to a SKU that has no details; the lost-update guard can be deleted with the suite green**
- Source: F-4-01.
- Location: DetailsRepository REPLACE_IF (`AND COALESCE(d.version, 0) IN (:versions)`); SkuDetailsPutIntegrationTest 188-221.
- Evidence (mine): in a scratch copy I removed that predicate and added one test (`Tables.seed(jdbc,"PM-9",2)`, `If-Match: "5"`, expect 412). SkuDetailsPutIntegrationTest: 54 tests, the 53 existing pass on the mutant, only the new test fails (the PUT answers 200 and creates the details). The production code is correct today, so this is a coverage gap on the OD-11 guard, not a live defect (a downgrade to MINOR would be defensible).
- Disposition: fix (test only): add a parameterized case (`"5"`, `W/"0"`, `"7", "2"`) on a detail-less SKU with stock to SkuDetailsPutIntegrationTest. See also M-25 (deterministic REPLACE_IF interleaving).

### MINOR

**M-05 [MINOR (src F-1-01 MAJOR, F-8-01 MINOR), CONFIRMED, DECISION CHALLENGE] /v2 strong ETag does not change when quantity changes**
- Sources: F-1-01, F-8-01.
- Evidence (mine): GET /v2/inventory/V-1 -> `ETag: "1"`, quantity 3; after an unversioned add of 10 -> `ETag: "1"`, quantity 13. RFC 9110 8.8.1 and 9.3.4 wording quoted by F-8-01.
- Why I downgrade: PROMPT Target API (line 79) fixes it: ETag = "the details version, "0" before any details", with `Cache-Control: no-store` and conditional GET ignored. So it is the owner's contract, harmless while `no-store` and ignored conditional GET hold; only becomes a trap if 304 is ever added.
- Disposition: DECISION CHALLENGE (not fixable by us). Record: in DESIGN section 2/README API table and the ETag card (A27), say "details validator, not a validator of the whole representation; RFC 9110 8.8.1 and 9.3.4 deviation known; honouring If-None-Match on GET would need /v3 or a separate details resource". No code change.

**M-06 [MINOR (src F-7-03 MAJOR), CONFIRMED, DECISION CHALLENGE] An unconditional details PUT to an unknown id creates a permanent phantom SKU; /v2 leniency (bad limit ignored, no precondition required) cannot be tightened later without /v3**
- Sources: F-7-03, F-1-07.
- Evidence (mine): with ABC-1 present, `PUT /v2/inventory/ABC-l/details` (no preconditions) -> 201 ETag "1"; `GET /inventory` (spec API) then lists `{"skuId":"ABC-l","quantity":0}`; `POST /inventory/ABC-l/purchase` -> 400 "Insufficient inventory" (not 404). No API deletes it (G5).
- Why I downgrade: the Target API (line 82: "If the SKU doesn't exist, it is created... Optional If-Match/If-None-Match") and OD-6/OD-11 prescribe unconditional create, and README 143 documents it; the front end always sends If-None-Match on Create. The gap is only that H7 does not list the typo cost.
- Disposition: DECISION CHALLENGE plus record: add the typo/no-delete cost and the /v2 lock-in (bad `limit` ignored, unconditional create) to card H7 and DESIGN (board, regenerate). No code change.

**M-07 [MINOR (src F-7-01 MAJOR), CONFIRMED (record gap), no owner decision] "Why 400, not 409/422, and why reject an expired key" is answered with a reason that does not hold on /v2, and the promised comparison is absent**
- Sources: F-7-01, F-8-06.
- Evidence (read): DECISIONS G14-B rejects 409/422 because they "add status codes the spec doesn't list", but /v2 already adds 412 (Target API line 82); DESIGN.md:52 says "compared in the critique"; T1 lists no "expired key means new request, as Stripe does" option. Area 8's table now supplies the comparison (draft -07 expired and archived, 422/409/400 wording, Stripe expiry).
- Downgrade reason: documentation; behaviour is disclosed and safe (no double write).
- Disposition: record: rewrite G14/T1 reasoning (real reason: keep one 400 for every request problem, matches the spec's single client-error code; accepted cost: client cannot tell mismatch, expiry, in-flight), cite draft revision -07 and its expired status, and replace "compared in the critique" in DESIGN.md:52 with the table from area 8.

**M-08 [MINOR (src F-7-02 MAJOR), CONFIRMED (record gap)] Path-versioning rationale is thin; provider comparison deferred to "the critique"; no deprecation or "compatible" definition**
- Source: F-7-02 (area 8's comparison table covers Stripe, GitHub, Shopify, AIP-185 with URLs).
- Evidence (read): DESIGN.md:21 "the critique compares them with sources (UNVERIFIED until then)".
- Disposition: record: rewrite the H1 reasoning around "the spec's URLs stay byte-for-byte; extensions are additive", paste the area-8 versioning row with its sources, delete "UNVERIFIED until then", add one line on deprecation ("removed only by an owner decision") and a definition of "compatible" (additive response fields and new optional inputs only).

**M-09 [MINOR (src F-7-04 MAJOR), CONFIRMED (record gap)] G9's rejected option D ("paged by default breaks the contract") describes what OD-5 does; a spec-only client gets 250 rows silently; the deviation is not on the README's first screen**
- Source: F-7-04 (plus the header note of area 7 on README line 3).
- Evidence (read): DECISIONS G9 line 257 vs choice E "Owner OD-5"; README:212 and openapi.yaml disclose the cap; README line 3 names neither deviation.
- Downgrade reason: OD-5 is the owner's decision and is disclosed twice; the remaining gap is one card line and one sentence.
- Disposition: record: reword G9-D's drawback ("superseded by OD-5"), add to H4/G9 the accepted risk (a client ignoring Link sees 250 of N with no truncation signal), add one sentence naming both deviations to README line 3.

**M-10 [MINOR (src F-7-05 MAJOR), CONFIRMED (record gap)] Superseded concurrency and ledger cards (D4, V1, W1) still read as the chosen design; reversal story scattered**
- Source: F-7-05.
- Evidence (read): D4 "Choice: D: SERIALIZABLE... Matched recommendation: No", V1 "Choice: C: Ledger only", W1 "B: SERIALIZABLE"; each carries a "Current rules... Superseded by E1" line, and V1 shows "Unresolved conflicts at export: About 4h more" (stale).
- Downgrade reason: the supersession is stated on each card and in E1/DESIGN 4; it is a presentation gap.
- Disposition: record: a short "concurrency design history" paragraph at the top of DESIGN section 4 (ledger-SUM plus SERIALIZABLE built, #26 40001 on different SKUs, moved to balance row plus append-only ledger at READ COMMITTED), and clear V1's stale "unresolved conflict" note on the board.

**M-11 [MINOR, CONFIRMED] Duplicate JSON keys are accepted, last one wins, on money-moving and details bodies**
- Sources: F-3-04, F-8-02.
- Evidence (mine): SKU with 1, `POST {"quantity":1,"quantity":2}` -> 200 quantity 3 (adds 2).
- Disposition: fix, small: `spring.jackson.parser.strict-duplicate-detection: true` (Jackson 3: StreamReadFeature.STRICT_DUPLICATE_DETECTION), 400 text/plain, one test per write route. Borderline under the "no new kind of check" rule (a parser differential can move stock by a different amount than a gateway saw); if the owner does not want a new check, record it under Assumptions.

**M-12 [MINOR, CONFIRMED, DECISION CHALLENGE (service text)] The 412 text on `If-None-Match: *` ("Details changed since you read them...") is wrong for Create; the Create page shows it as a jargon headline**
- Sources: F-5-02, F-7-07.
- Evidence (mine): `PUT /v2/inventory/ABC-1/details` with `If-None-Match: *` on an existing SKU -> 412 with exactly that text. Text is fixed by Target API line 82 and H8. F-5-02 (front end) by reviewer's Playwright output, not re-run.
- Disposition: service text = DECISION CHALLENGE, record in H8 that the text is misleading for If-None-Match and the client compensates (FE33/FE34). Front end = fix: on a 412 from Create show only the EXISTS sentence as the headline (as done for kind==='lost'), test in CreateSkuPage tests.

**M-13 [MINOR, CONFIRMED (code read)] Edit save shows "Sending again is safe" after a 5xx, but a resend after an applied save is a 412**
- Source: F-5-03.
- Evidence (read): EditSkuPage.tsx:134 uses writeGuidance(failure.status), Messages.tsx:21 returns retrySafe for retryable statuses; Edit sends no key and an If-Match tag (FE37 says retry is safe only after Reload).
- Disposition: fix (front end): on Edit, for retryable statuses say "The save may or may not have been applied. Reload to see the current details."

**M-14 [MINOR, PLAUSIBLE] A stalled row lock or a Postgres outage exhausts the 10-connection Hikari pool; unrelated requests 500 after 30 s; liveness starves at 200 threads**
- Sources: F-2-01, F-6-01.
- Evidence: I confirmed application.yaml has no hikari.*, lock_timeout or statement_timeout; the 30 s and starvation runs are the reviewers' (t6.py, docker stop test), not re-run.
- Disposition: fix (config, one line each): `spring.datasource.hikari.connection-timeout: 3000` and a Postgres `lock_timeout` (connection init SQL or `?options=-c lock_timeout=5000`); 500 "Internal server error" for a lock timeout stays within the allowed 500-for-server-faults. Or record under "Designed, not built".

**M-15 [MINOR, CONFIRMED] `Content-Disposition: inline;filename=f.txt` leaks onto responses for dotted SKU ids**
- Source: F-1-02.
- Evidence (mine): `GET /inventory/A.b` (404 here) and `POST /inventory/A.b` carry the header; unversioned API is "the spec plus frozen hardening and nothing more".
- Disposition: fix small (strip the header for /inventory/** and /v2/** in the existing guard filter, one header-set test on a dotted SKU) or record as Spring's reflected-file-download guard. Not a status or body change.

**M-16 [MINOR, CONFIRMED] Integer-valued floats (5.0, 1e2) are rejected though the published OpenAPI is 3.1, where 5.0 is a valid integer**
- Source: F-3-03.
- Evidence (mine): `POST {"quantity":5.0}` -> 400 "Invalid request".
- Disposition: record (G13 is deliberate): add to the `quantity` description in both OpenAPI files "written without a fraction or exponent" (ApiDocsTest and export pick it up) and one README Assumptions line. No behaviour change.

**M-17 [MINOR, CONFIRMED] The next-page Link is built from the client-supplied Host header**
- Source: F-8-04.
- Evidence (mine): `Host: evil.example` on `GET /v2/inventory?limit=1` -> `Link: <http://evil.example/v2/inventory?limit=1&after=A.b>; rel="next"`.
- Disposition: record (won't fix now): a relative Link or configured base URL changes the OD-5 Link shape; add a README line "Link is built from the request's Host (behind a proxy set the Host)". Owner decision if they want a configured base.

**M-18 [MINOR, CONFIRMED] Error Content-Type is `text/plain` from the advice and `text/plain;charset=UTF-8` (chunked) from the interceptor, guard filter and Tomcat valve**
- Sources: F-1-04, F-3-08 (first bullet).
- Evidence (mine): POST with `Accept: application/xml` -> `text/plain;charset=UTF-8`; body-cap 400 the same; advice errors are `text/plain` with Content-Length.
- Disposition: won't fix; record in README Assumptions (same media type, charset parameter differs) . Unifying touches the frozen guard path for no client benefit.

**M-19 [MINOR, CONFIRMED] openapi-v2.yaml documents no `Cache-Control: no-store` (sent on GET one, list and PUT 200/201) and not the Tomcat 400 on GET one (%5C, %00)**
- Sources: F-1-05, F-3-08 (second bullet), F-8-03.
- Evidence (mine): `GET /v2/inventory` sends `Cache-Control: no-store`; `grep -ci cache-control openapi-v2.yaml` = 0.
- Disposition: fix small (document the header on the /v2 200/201 responses via @Header in the v2 controller, regenerate and commit openapi-v2.yaml through the export test) or record in README. F-8-03's suggested fix (add no-store to the unversioned reads) is REJECTED: it would leak an extension onto a spec path (PROMPT invariant 1).

**M-20 [MINOR, CONFIRMED] A path Tomcat rejects (%00, %5C, bad escape) answers 400 on GET /inventory/{skuId} while every other invalid id is 404; the spec lists no 400 for GET**
- Source: F-1-03.
- Evidence (mine): `/inventory/A%5CB` 400 "Invalid request"; `/inventory/%C3%A9` 404 "SKU not found".
- Disposition: record (C1, accepted hardening): one line in README Assumptions and openapi.yaml info.description.

**M-21 [MINOR, CONFIRMED, DECISION CHALLENGE] Unversioned GET /inventory answers 400 for any undecodable query, even an unrelated parameter; the spec lists no 400 there**
- Source: F-1-08.
- Evidence (mine): `GET /inventory?x=%zz` -> 400; `GET /inventory/ABC-1?x=%zz` -> 200.
- Disposition: DECISION CHALLENGE (Z3/C1 are recorded decisions and documented in openapi.yaml). Record only; changing it means custom query parsing.

**M-22 [MINOR, PLAUSIBLE] ArchUnit rule "unversioned service methods reach no idempotency type" does not follow method references**
- Source: F-4-02 (mutation A11 survived per reviewer).
- Disposition: fix (test): add JavaMethodReference and constructor references as edges in the worklist in ArchitectureTest 151-176. Hardens an existing rule, not a new kind of check.

**M-23 [MINOR, PLAUSIBLE] Five vitest tests assert the MSW mock, not the client or service; the mock drifts from Java in three small ways**
- Source: F-4-03 (limit=+5, quantity 2147483648, no 24 h expiry or body cap in the mock; none reachable through the UI).
- Disposition: record; optionally align the mock (two lines) or delete the five "mock ..." tests. Low value.

**M-24 [MINOR, PLAUSIBLE] The Link re-rooting in client.listSkusAt is untested (mutant F12 survives)**
- Source: F-4-04.
- Disposition: fix (test): one vitest where the Link is `http://service:8080/v2/inventory?...` and the request must go to the base origin.

**M-25 [MINOR, PLAUSIBLE] Concurrency tests never assert overlap; the REPLACE_IF row-lock recheck is guarded by one racy test**
- Source: F-4-05 (M14 killed only by SkuDetailsPutConcurrencyTest).
- Disposition: fix (test): add one deterministic two-connection interleaving for REPLACE_IF, as `findNeverWaitsOnAWriterHoldingTheRowLock` does for reads. Pairs with M-04.

**M-27 [MINOR, CONFIRMED (grep)] `sku.version` and `Balance.version` are written on every stock change and never read; tests pin the column**
- Sources: F-4-07, F-9-04.
- Disposition: won't fix (dropping the column needs a V5 migration; V1-V4 are never edited): record one line "reserved; nothing reads it" or leave. Tests pinning it stay.

**M-28 [MINOR, PLAUSIBLE, DECISION CHALLENGE (OD-4)] Unversioned POSTs reject any Idempotency-Key with 400 although RFC 9110 6.3 says recipients SHOULD ignore unknown headers; a client without UUID support and the front end's in-memory key have unstated limits**
- Sources: F-8-05, F-7-08.
- Disposition: DECISION CHALLENGE for OD-4 itself (describe, do not change). Record: add the RFC sentence and the counter-argument ("fail loudly instead of silently double-writing") to OD-4/H3, and two README sentences: how a client without UUIDs gets a key (generate any random UUID v4), and that key-reuse safety lasts while the tab lives (FE34/FE33).

**M-29 [MINOR, PLAUSIBLE, DECISION CHALLENGE] H9 (NOT VALID migration) and H10 ("v2\n" hash prefix) protect databases from build v2 that E3 says are never upgraded; premise unrecorded**
- Sources: F-7-10, F-9-07.
- Disposition: record the premise in H9/H10 ("defensive; no build-v2 database is claimed"); do not remove (migrations are frozen, prefix removal is a behaviour change for migrated DBs). Owner decision if they want it dropped.

**M-30 [MINOR, CONFIRMED] frontend/DECISIONS.md has stale statements: FE34 (step 2 is a keyed unversioned POST; "add moves to /v2 in a later PR"), FE42 ("not run in this session"), FE13, FE24**
- Source: F-7-11.
- Evidence (mine): grep shows the FE34 and FE42 sentences at lines 38 and 46.
- Disposition: fix (docs): amend or strike those sentences in frontend/DECISIONS.md (hand-maintained per OD-9).

**M-31 [MINOR, PLAUSIBLE] Test volume (about 10.4k test lines vs 2.6k main, ArchUnit) has no recorded rationale**
- Source: F-7-12.
- Disposition: record: three README lines ("what the tests protect and what they skip"; ArchUnit reason from H15/lessons).

**M-32 [MINOR, PLAUSIBLE] The documented board regeneration command fails (`ERR_MODULE_NOT_FOUND` for playwright from ai/); the working command lives only in ai/final/plan.md**
- Source: F-7-09 (byte-identical regeneration confirmed by the reviewer when run from frontend/).
- Disposition: fix (docs): two-line "Regenerate the board export" snippet in README or ai/ (`cd frontend && npx playwright install chromium && node ../ai/export-board.mjs ...`).

**M-33 [MINOR, CONFIRMED] README: walkthrough hard-codes `localhost:8080` 27 times although APP_PORT is advertised; first screen does not name the two deviations; DESIGN/board text carries process talk; a doubled `/v3/api-docs.yaml/{group}` phrase**
- Sources: F-7-13 (ports part), F-1-10 (README part), area-7 header notes (unnumbered), see M-09.
- Evidence (mine): `grep -c localhost:8080 README.md` = 27; README line 7 says "port 8080 free (or set APP_PORT)".
- Disposition: fix (docs): use `${APP_PORT:-8080}` in the Try-it commands or one sentence "replace 8080 if you set APP_PORT"; remove process phrases (DESIGN.md:21/52 covered by M-07/M-08); fix the duplicated phrase.

**M-34 [MINOR, CONFIRMED] No CI runs: `.github` is absent, the workflow is parked at ai/final/ci-workflow.yml**
- Sources: F-6-08, F-7-13 (CI part).
- Evidence (mine): `ls -a .github` -> no such directory. README:217 already says so honestly.
- Disposition: record: add the date and counts of the last green `scripts/gate.sh` run to the README; commit the workflow when the token has the scope.

**M-37 [MINOR, PLAUSIBLE] Simplification candidates (about -210 lines net, proven building green in scratch by the reviewer): duplicate unversioned read path, identical toResponse switch in both controllers, dead join-a-transaction logic and ApiVersion in IdempotencyStore, duplicate Link tests**
- Sources: F-9-01, F-9-02, F-9-03, F-9-06 (patches /private/tmp/claude-501/critique/area9-patches/E-a.patch, E-b.patch, E-c.patch).
- Disposition: won't fix now (late hardening, a large refactor for no behaviour change; F-9-01 also changes the E1 board rule "unversioned reads join nothing", and E-c needs the A33 card edited, so both need the owner). If anything, take E-a (ApiVersion leftovers) and F-9-02 (`OutcomeResponses.toResponse`) only.

### NIT

**M-26 [NIT, PLAUSIBLE] Validation matrices repeat across 4 to 7 test files per rule** (F-4-06). Disposition: record (name one owner per rule in a comment).

**M-35 [NIT, PLAUSIBLE, record] Idempotent replay returns the stored point-in-time snapshot (stale details or quantity)** (F-2-03). Disposition: one README sentence "replayed bodies are point-in-time; GET before If-Match".

**M-36 [NIT, PLAUSIBLE, record] "Append-only by trigger" is bypassable by the app's own superuser role (TRUNCATE, DISABLE TRIGGER, direct UPDATE sku)** (F-2-02). Disposition: record, already weighed and rejected in E3 (privilege separation); state "against the API, not against the DB owner" in DESIGN.

**M-38 [NIT, CONFIRMED] Dead sealed sub-interfaces `Add` and `Purchase` in StockOutcome** (F-9-05). Evidence (mine): the only references are in StockOutcome.java. Disposition: fix (delete, six lines) or leave.

**M-39 [NIT, CONFIRMED (grep for the first item)] Small test items: `@AllowsBalanceMismatch` used by no test; e2e `posts` assertion evaluated without a wait; useIdempotentSubmit guard has no test of its own; text-scan tests; two Playwright projects share one DB; a11y in one state only** (F-4-08). Disposition: fix the dead annotation (delete), record the rest.

**M-40 [NIT, PLAUSIBLE] Front end: dev-only StrictMode double effect steals initial focus into `<main>`; `#/sku/x/edit/` parses as SKU id `x/edit/`** (F-5-04, F-5-05). Disposition: fix small or record (ref tracks the previous route; reject ids containing `/` in parseRoute).

**M-41 [NIT, PLAUSIBLE] Ops nits: floating image tags and trivial DB password, app port bound on all interfaces (compose.override.yaml `"${APP_PORT:-8080}:8080"`, confirmed by reading), springdoc production warning not explained, no nosniff header, Tomcat header-parse stack trace at INFO** (F-6-04, F-6-05, F-6-06, F-6-07). Disposition: record; optional `127.0.0.1:` bind and one README sentence on `springdoc.api-docs.enabled`. Do not add security headers (extension on spec paths).

**M-42 [NIT, PLAUSIBLE] Every 400 closes the connection (Tomcat); "every path" wording of the undecodable-query rule is not literally true (`/nope?x=%ZZ` -> 404, `/v3/api-docs?x=%ZZ` -> 200)** (F-1-09, F-3-07). Disposition: record (reword "every path that reads its query").

## Rejected

- **M-43 [REJECTED] F-3-06 "`after` is not compared as a plain string: a NUL byte truncates it".** No observable difference: NUL is the lowest byte and ids cannot contain it, so `after=S%00x` and `after=S` select exactly the same rows (I ran `after=ABC-1%00x` and `after=ABC-1`: identical pages). The mutation M25 in area 4 only pins an internal detail.
- **F-8-03's proposed fix** (Cache-Control on unversioned reads) and F-6-06's optional headers: rejected as fixes because they would add response headers to spec paths (invariant "no extension leaks"); the findings themselves live on in M-19 and M-41.

## Per-area "checked and fine" highlights

1. Spec and versioning: byte-level 200/400/404 on all four spec operations; version parity walk over 310 SKUs identical in both versions; every documented /v2 status observed.
2. Concurrency: no oversell across versions (20 SKUs x 30 purchases, 64 threads); 50 concurrent same-key requests apply once; Postgres SIGKILL under keyed load loses and doubles nothing; no deadlocks in 4000 mixed requests; overflow guard at Long.MAX.
3. API contract: live OpenAPI equals the committed YAML; Schemathesis findings other than M-16/M-19 were artifacts; the full raw-socket list of Tomcat rejections is text/plain; Accept, G13 and key matrices all as documented.
4. Tests: 960 Java, 248 vitest, 34 Playwright green three times with no flake; 57 Java mutations, 52 killed with behavioural tests; every SQL rule runs against real Postgres; balances extension proved not vacuous.
5. Front end: only /v2 is ever called; key lifecycle and double-submit behave as recorded; server text rendered as text; axe clean; npm audit 0.
6. Security/ops: no SQL injection or path confusion; smuggling forms rejected; TRACE never echoes; only health exposed; non-root container; keyset queries use indexes at 100k SKUs; graceful shutdown works.
7. Records: READ COMMITTED reasoning, E3/A11/A14 trigger reasoning, H6, H9, H15 and the CI-parked and not-retry-safe disclosures are answered with a reason and an alternative; every README Try-it command matched.
8. Standards: 201 on PUT create, 405 with Allow, Link syntax per RFC 8288, health split, OpenAPI files without 3.0-only constructs; differences from Stripe/IETF draft are all deliberate and mostly recorded.
9. Simplification: guard filter, RoutedPath, SkuId placement, KeyedResponses, ledger and both OpenAPI files were examined and are not removable.

## Open owner decisions

1. M-01: lift the A19 freeze so unversioned chunked bodies are capped at 4096 (and add a compose mem_limit)? Recommended yes; this is the only frozen item a MAJOR touches.
2. M-05: keep ETag = details version (Target API) and record the RFC 9110 deviation, or move to a separate details resource in a future /v3?
3. M-06: keep unconditional PUT-create and "no delete" (record the typo cost), or require a precondition (428) for creation?
4. M-12: keep the single 412 text for If-None-Match (front end compensates) or add a second text?
5. M-21 and M-17: accept the 400 for an unrelated undecodable query and the Host-derived Link, or change them?
6. M-28 (OD-4): keep rejecting the key on unversioned POSTs (record the RFC 9110 6.3 counter-argument)?
7. M-29: keep H9/H10 protection for build-v2 databases that nobody is claimed to have, or drop them?
8. M-11: accept one new check (strict duplicate JSON keys) or record it as a known ambiguity?
9. M-37: any of the simplification patches at all before submission (E-a and the shared toResponse are the safe ones)?

## Cheapest high-value fixes (in order)

1. M-04: one parameterized test row (already written and proven in scratch: `If-Match: "5"` on a detail-less SKU).
2. M-03: `overflow-wrap: anywhere` on `h2, .kicker` plus one Playwright case.
3. M-02: answer `/error` with 404 text/plain and route the ErrorPage dispatch through TextErrors.
4. M-01: CappedBodyRequest on the unversioned writes and `mem_limit` in compose.
5. Docs batch (no code): M-07, M-08, M-09, M-10 card rewordings, M-30, M-33, M-34, M-16 OpenAPI sentence.
6. M-13 and M-12 front-end text changes; M-14 two config lines; M-11 one property.
