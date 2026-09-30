# Area 7: Interview defense (SHA 436cacae)

Process records read (allowed for this area): README.md, DESIGN.md, DECISIONS.md, CLAUDE.md, frontend/DECISIONS.md, ai/final/PROMPT.md, plan.md, board-cards.md, lessons.md, current-state.md (one grep hit), ai/export-board.mjs, ai/final/board-db.
Not read: log.md, deviations.md, board-followups.md, critique*, report.md, interview-defense.md, PR/issue threads.
Line numbers for DECISIONS.md are from the committed file; card ids are given so they can be found after edits.

## What a reviewer sees first (README top screen)
- Line 3 is one 160-word paragraph. It says the spec's four operations are served "exactly as the spec describes them plus two recorded deviations", and that everything else lives under /v2.
- It does not name the two deviations (list capped at 250; Idempotency-Key answered 400). They sit at README.md:208-213, roughly 200 lines down.
- The same paragraph links a private artifact (https://claude.ai/artifact/5SCRV...) that an outsider cannot open. It does say the DB is dumped in ai/final/board-db, which I verified reproduces byte for byte (see F-7-09).
- Verdict: honest, not oversold. There are no performance claims ("No performance claim", line 206), and the "Deviations" and "Designed, not built" sections are candid. But the one deviation that changes what a spec client receives (a silently truncated list) is not on the first screen. Suggest adding one sentence to line 3: "the list is capped at 250 with a Link; unversioned POSTs reject Idempotency-Key".
- Docs that should never reach a reviewer leak process talk: DESIGN.md:21 "the critique compares them with sources (UNVERIFIED until then)", and board-cards.md "area 7 / area 8 compares".

## Try it walkthrough (README:95-173) on my stack (APP_PORT=18207, project crit7)
- Every command in "The spec API", "The /v2 API", Details and Paging produced the documented status, headers and body (ETag "1"/"2"/"0", Link with limit and after, 412 text, replay, key mismatch 400).
- Nothing failed. Confusions:
  - Every command hard-codes localhost:8080, although APP_PORT is advertised. Use `${APP_PORT:-8080}`, or say so.
  - `?limit=2` on the unversioned list silently returns all rows (documented, but surprising).
  - The typo case in F-7-03 works exactly as feared (see below).
- Stack teardown was clean.

---

### F-7-01 [MAJOR] "Why 400, not 409/422, for key reuse and expiry" is answered with a reason that no longer holds on /v2
- Question: The IETF draft and Stripe use different codes for mismatch and in-flight. Why did you use 400 for everything, and why reject an expired key instead of treating it as new (as Stripe does)?
- Records: DECISIONS.md G14 (about line 244): "B: IETF draft codes (409 / 422). Drawback noted in research: Adds status codes the spec doesn't list." DESIGN.md:52: "This is a deliberate choice of 400 over 409 or 422; the IETF Idempotency-Key draft and Stripe differ (compared in the critique)." board-cards H2: "Trade-off accepted: 400, not 409/422 ... (area 8 compares)". T1 (line 647): the rejected options are all SQL-shape variants ("The claim SQL gets longer"); the real alternative, "treat an expired key as a new request, like Stripe", is not listed. No card says why an expired key is refused.
- What is missing:
  - (a) The stated reason ("codes the spec doesn't list") applies to the unversioned API. /v2 already adds a code the spec does not list (412, H7/H8), and G10 says "listed codes on /v2", so the argument is void where the choice lives.
  - (b) The comparison the docs promise is not in the repo; DESIGN.md still reads "UNVERIFIED until then".
  - (c) The honest cost is not written down. A client that retries after 24 h gets 400 and cannot tell "different request", "expired", or "first attempt applied". Nor can it tell "concurrent duplicate in flight" from a bad request, because there is no 409. That is exactly the ambiguity an idempotency key exists to remove.
- Evidence: README.md:124, verified: same key with quantity 6 gives `400 Invalid request`, byte-identical to a missing key.
- Suggested fix: add a card (or rewrite G14 and T1) stating the real reason, the rejected "expired means new" option, and the accepted cost; delete the "UNVERIFIED until then" sentence.

### F-7-02 [MAJOR] The "why path versioning, not header/date versions" answer is thin and partly wrong
- Question: Stripe, GitHub and Shopify use dated header versions and Google AIP-185 uses major-version paths. Why did you pick paths, and what is the /v3 policy?
- Records: H1 (DECISIONS.md line 1027): "My reasoning: Owner OD-2". The rejected reasons are "A header is invisible in a URL, a curl line and the Swagger UI, and needs a default for callers that send none", and for a query parameter "caches key on the URL". DESIGN.md:21 repeats it and adds "The price is two route sets and two OpenAPI files." The provider comparison is deferred to "the critique" and never shipped.
- What is missing or weak:
  - (a) "Needs a default for callers that send none" is not an argument. Stripe and GitHub have a default, and the price is a pinned account version. Here the unversioned path is itself the default and is frozen, which is the same thing.
  - (b) The one real reason, that the take-home spec's URLs must stay exactly the spec's while extensions are additive, is only implied (H1 rejected D). It is not stated as the deciding factor, and it would be an honest, strong answer.
  - (c) The /v3 policy says "/v2 only grows compatibly" and "old prefixes are removed only by an owner decision" (DESIGN.md:19). There is no deprecation or sunset story and no definition of "compatible". For example, is adding a required field to a response compatible for a strict client? Is the unversioned API ever versioned? The v1 and v2 mix (unversioned = "1.0.0") is not explained.
  - (d) The card's "My reasoning" is a pointer to an owner decision, not a reason.
- Suggested fix: rewrite H1's reasoning around "the spec's contract stays byte-for-byte; extensions are additive", add a two-line comparison with a source URL for each provider, and add one line on deprecation.

### F-7-03 [MAJOR] An unconditional details PUT to a typo'd id creates a permanent junk SKU that shows up in the spec's list
- Question: What does `PUT /v2/inventory/ABC-l/details` (lowercase L) do? Can it be undone?
- Records: board-cards.md H7: "Rec / open: an unconditional PUT to an id nobody has created will create it (typo risk) ... Interview question". README.md:143 documents create-if-missing. G5 and the ledger trigger mean SKUs are never deleted ("Rows are never deleted"). The only mitigation is that the front end sends `If-None-Match: *` on Create.
- Evidence (my run): after seeding ABC-1, `PUT /v2/inventory/ABC-l/details` with no preconditions returned `201 ETag "1" {"skuId":"ABC-l","quantity":0,...}`. Then `GET /inventory` (the spec API) listed `{"skuId":"ABC-l","quantity":0}` next to ABC-1. A spec client sees a phantom SKU with 0 stock, and `purchase` on it answers 400 "Insufficient inventory" instead of 404. There is no API to remove it.
- What is missing: no card records the typo consequence as a decision or lists "no delete" as an accepted cost. H7's rejected options (404 or 409) are refuted only by "needs a second route". The clean alternative was never weighed: make `If-None-Match: *` (or `If-Match`) required for the create-branch, and answer an unconditional PUT to an absent SKU with 404, or 428 Precondition Required.
- Suggested fix: record the typo cost in H7 as a trade-off, and either require a precondition for creation or say plainly that "no delete" is a scope choice.

### F-7-04 [MAJOR] 250 cap on the spec path: G9's rejection of "page by default" is contradicted by the choice
- Question: "You changed the contract. The spec says 'a list of all SKUs.' Defend it."
- Records: G9 (DECISIONS.md about line 248) chose E: fixed 250. Its rejected option D, "Paged by default (e.g. 50). Drawback: A client that sends no params no longer gets all SKUs, which breaks the contract" is exactly what the chosen option does. The reason for the cap is C-02 (an OOM at 1M SKUs in build v1's critique), stated in README:212 and C2. C2 does reject "Stream every row" with a reason, which is good. The spec text: "Returns a list of all SKUs currently in inventory" (docs/NUULY-ASSESSMENT-README-JUL-2026.md:116).
- What is missing:
  - (a) The rejection text of G9-D undermines the choice, so an interviewer reading DECISIONS.md finds the card arguing against itself.
  - (b) The failure mode for a spec-only client is not spelled out: it reads the JSON body, ignores the Link header, and silently gets 250 of N rows. No error signals truncation. Alternatives that keep the contract or fail loud (streaming JSON, or 4xx when the count exceeds the cap, or an `X-Total-Count`) are not in any card apart from streaming.
  - (c) The C-02 evidence (1M SKUs) lives in an earlier build's critique. The docs cite it, but a reviewer cannot reproduce it. ai/codebase-critique.md may hold it; I did not verify.
  - (d) The deviation is disclosed well (README:208, openapi.yaml info.description).
- Suggested fix: delete or reword G9-D's drawback, and add one sentence to H4 stating the truncation risk for clients that ignore Link and why it was accepted.

### F-7-05 [MAJOR] DECISIONS.md still shows superseded concurrency and ledger choices, with reasoning that describes the opposite design
- Question: You chose SERIALIZABLE with a SUM ledger (V1, W1, D4), then switched to a balance row at READ COMMITTED (E1). Why, and why does DECISIONS.md still say the first?
- Records: D4 "Choice: D: SERIALIZABLE isolation + retry", "Matched recommendation: No". V1 "Choice: C: Ledger only (balance = SUM of deltas)", reasoning: "closer alignment with shopify ... permanently deduct quantity from the inventory ledger (source of truth)", and "Unresolved conflicts at export: About 4h more, plus a new concurrency design". W1 "B: SERIALIZABLE transactions with retry". Only the trailing "Current rules ... Superseded by E1" lines say otherwise. E1's own "My reasoning" is "Approved at the plan gate (2026-09-29)"; the real reason ("Writes to different SKUs still fail with 40001 under SSI (#26)", "every write sums a ledger that only grows") is inside rejected option B. V1's owner-preferred hybrid rejection ("B: Hybrid: balance row + ledger row ... About 2h") is what E1 now builds.
- What is missing:
  - (a) A one-paragraph history: "we built ledger-SUM plus SERIALIZABLE, measured or hit #26, and moved to balance row plus append-only ledger". Only the outline is there, spread across ten cards.
  - (b) The duplication cost: `sku.quantity` and `SUM(quantity_delta)` can drift. A14 (line 951) says the invariant is "asserted by tests after every write test", so production has no DB constraint or reconciliation job. That is honest but should be stated as accepted risk. The "why a trigger" answer (E3) covers row-level append-only triggers, and A14-B/C reject balance-by-trigger with reasons. That is fine.
  - (c) No measurement of the #26 problem is cited in the repo.
- Suggested fix: add a top-of-file "concurrency design history" paragraph to DESIGN.md §4, and fix V1's reasoning to say it was reversed.

### F-7-06 [MAJOR] The documented 4 KB body cap is not enforced on chunked unversioned POSTs (verified); "frozen from build v2" is a process reason, not an engineering one
- Question: You wrote "A POST body over 4 KB is 400 (A19)". Then you listed "unversioned chunked bodies are uncapped". Why not fix it?
- Records: README.md:197 says a POST body over 4 KB is 400; README.md:223 says "Unversioned chunked bodies are uncapped ... /v2 counts a chunked body while reading". A19 card says "4 KB on both POSTs". DESIGN.md:74: "changing one is a decision for the owner". Same handling for M-13 (H16) and the Accept tie-break (C3-D).
- Evidence: I sent an 8 MB chunked body (`Transfer-Encoding: chunked`, `{"quantity":1,"x":"aaa..."}`) to `POST /inventory/CH-1`. Result: `200 {"skuId":"CH-1","quantity":1}`. The same body on `/v2/inventory/CH-2` (with a key) gave 400.
- What is missing: no reason other than "frozen". The code to count chunked bodies exists for /v2, so the cost of fixing is small, and it is unauthenticated: an OOM vector of the same kind as C-02. The A19 card says 4 KB on both POSTs, so the card is false as written. The Accept tie-break has the same shape: CLAUDE.md:66 reads "first listed decides (the frozen behaviour; this card once said the highest q)", and the rejected "highest q" option has no reason of its own beyond authority (C3-A: "the frozen behaviour wins where C3 disagrees").
- Suggested fix: apply the /v2 chunk counter to the unversioned POSTs, or rewrite A19 and the README assumption to "Content-Length only" and record why.

### F-7-07 [MINOR] The 412 text on `If-None-Match: *` is wrong, and both the card and the front end know it
- Question: `PUT` with `If-None-Match: *` on an existing SKU says "Details changed since you read them". Nobody read anything.
- Records: H8 (line 1096): "B: A second text for If-None-Match on an existing SKU. The Target API fixes one text", and board-cards H8: "The text reads oddly on the Create page (nobody read anything); the front end shows the server text and adds its own line 2." README:152 shows it verbatim. FE33/FE34 overrides the message in the UI because the server's text "would be false here".
- Evidence: my run returned exactly that text for `If-None-Match: *` on LN-1.
- What is missing: the trade-off is accepted only by reference to the Target API ("owner"). The honest answer, "it is a wrong message on an existing SKU and the client compensates", is present in the front-end card but not on the service side. It would be embarrassing to be asked live.
- Suggested fix: use a second text for If-None-Match (or one neutral text such as "Precondition failed"), and reword H8.

### F-7-08 [MINOR] Required keys on /v2 and retry-safety of the spec POSTs: answers exist but leave gaps
- Question: What does a client that cannot make UUIDs do? "So your spec API is not safe to retry?"
- Records: README.md:83 states the second plainly ("Unversioned POSTs are not retry-safe, and they reject the key ... a caller that needs retries uses /v2"), and DESIGN.md:55 and 86 repeat it. That is a good, honest answer. A17 (line 1179): "UUID", rejected "any opaque string: Nothing validates it", which does not answer the first question. There is no server-issued-key option and no "any 8 to 64 printable characters" option considered.
- What is missing:
  - (a) The first question is answered nowhere.
  - (b) A hard 400 on the spec endpoint for any request carrying the header is a compatibility cost: gateways and SDKs (some HTTP clients add idempotency headers to POSTs automatically) turn a working call into a permanent failure. H3 rejected "ignore" for the reason "a caller who sends a key believes the write is retry-safe". That reason is sound but makes the costs asymmetric: the caller is punished for a header the spec never forbade. Neither card states the cost.
  - (c) The front end's key is held in memory only; there is no persisted key. FE34 says "A refresh or leaving between the two steps loses the initial quantity ... the user adds stock from the SKU page". The next add is a fresh key. If the first add was applied but its response was lost, the refresh path can double-add. FE33's "Sending again is safe" holds only within one page lifetime, and that limit is not stated.
- Suggested fix: add two sentences to README "API versions": how a client without UUIDs gets a key, and that key reuse safety lasts while the tab lives.

### F-7-09 [MINOR] Board reproducibility works, but not as documented
- Question: Can an outsider reproduce DECISIONS.md and CLAUDE.md without the private artifact?
- Evidence:
  - `node ai/export-board.mjs ai/decision-board.html ai/final/board-db <out> 2026-09-29` after `npm ci` in frontend/ fails: `ERR_MODULE_NOT_FOUND` (the script imports `playwright` from ai/, which has no node_modules; ESM ignores NODE_PATH).
  - Copying the script into frontend/ and running it there produced DECISIONS.md and CLAUDE.md byte-identical to the committed files (`cmp`: same). Good: the process is reproducible.
  - README (lines 3 and 239) says only "reproduced with ai/export-board.mjs". The working command (with Playwright "next to the script") is in ai/final/plan.md only.
- What is missing: exact steps in the README; and the board is the sole edit path ("hand edits forbidden", S9), yet the board HTML (ai/decision-board.html) and the dump are the source, so a reviewer can rebuild but cannot use the board UI (private artifact). That is defensible; state it.
- Suggested fix: add a two-line "Regenerate the board export" snippet to ai/ or the README (`cd frontend && npx playwright install chromium && node ../ai/export-board.mjs ...`) or move the script's Playwright import to frontend.

### F-7-10 [MINOR] Legacy-data protections contradict each other (H9, H10 vs E3)
- Question: E3 says "no backfill, no upgrade from main". Why then do you spend a NOT VALID migration (H9) and a version-salted hash (H10) protecting databases that hold build v2 rows?
- Records: H9: "a database that still holds a create, 201 or 409 row migrates and keeps reading it"; H10: "A key stored by build v2's unversioned POST would replay an InventoryItem body on /v2". E3 (line 930): "V1 carries the balance row and triggers; no backfill, no upgrade from main". DESIGN.md:35 says V1-V3 "are published on v2 and never edited".
- What is missing: the reason build v2 databases exist. No deployment of build v2 is claimed anywhere. Whether there is a live v2 database is the premise of both cards, and nobody records it. The honest answer may be "defensive; nobody has one". That is over-engineering for a take-home. It also undercuts E3's stance that old databases do not upgrade.
- Suggested fix: state the premise in H9 and H10, or drop the migration and salt.

### F-7-11 [MINOR] Stale statements in frontend/DECISIONS.md (a reviewer will read it)
- Evidence:
  - FE34 says step 2 is a "keyed `POST /inventory/{skuId}`" (the unversioned path, which would answer 400 now) and "The add moves to /v2 with the stock forms in a later PR". The code uses `/v2/inventory/{skuId}` (frontend/src/api/client.ts:51,59) and FE38 says it moved.
  - FE42: "The specs were written but not run in this session: the service on the run's v2 tip has no v2 endpoints yet".
  - FE13: "Playwright ... is not run in CI without a service".
  - FE24 refers to "build v2 used 15173/18080".
- What is missing: OD-9 keeps this file hand-maintained, yet it contradicts the running code and FE38. That undermines "records match code" as a selling point.
- Suggested fix: amend or strike FE34's stale sentences and FE42.

### F-7-12 [MINOR] Test volume and ArchUnit have no recorded rationale
- Question: 960 tests and about 10.4k test lines for about 2.6k lines of main. Isn't that over-tested? Why ArchUnit?
- Evidence: `find src/test -name '*.java'` totals 10,361 lines; `find src/main` totals 2,624. 377 @Test or @ParameterizedTest methods in 53 files. ArchitectureTest has 9 rules. The 960 count I could not find in any doc (UNVERIFIED as a number); README states no test count.
- Records: lessons.md and H15 justify ArchUnit ("rules written as text were still broken", L19/L27/L31), which is a good answer. Nothing justifies the breadth of tests against the cost of maintaining them or names what was deliberately not tested.
- Suggested fix: add three lines to the README ("what the tests protect and what they skip"); mention which tests are redundant per version (both versions repeat the hardening matrix).

### F-7-13 [NIT] Parked CI workflow, ports 8080/5173
- CI: README:217 is direct and honest (no `workflow` scope; parked at ai/final/ci-workflow.yml; never run on GitHub; local gate as merge gate; exact owner command). Weak point for the interview: the honest answer to "so nothing ran your tests on a clean machine?" is "the gate script did", and no recorded gate output is committed in README (preflight.md may have some). Adding the last green `scripts/gate.sh` output, or the date and counts, would help.
- Ports: H13 justifies 8080/5173 by "the owner asked for main's ports". That is an instruction, not a reason; the README reviewer text (port 8080 free, or set APP_PORT) is adequate. Both fine. The Try-it commands ignore APP_PORT (see above).

---

## Three questions I would dread as the interviewee, and where the records stand
1. "Your list endpoint stops returning all rows and never says so." Records: the README, openapi.yaml and Link header disclose it, but G9-D's rejection text contradicts the choice and there is no answer for clients that ignore Link (F-7-04). Weakest: no truncation signal.
2. "Show me how you'd delete the typo SKU your PUT just created." Records: not answerable; SKUs are never deleted and the PUT creates without a precondition (F-7-03). Only an "open interview question" note in board-cards.md, a file that is process material, not shipped guidance.
3. "You chose SERIALIZABLE, told me it was the guard, then replaced it. What did you learn, and where is that written?" Records: the outcome is in E1 and DESIGN.md §4, but the reversal story and evidence (#26) are scattered and the V1/D4/W1 choices still read as current (F-7-05). Also related: "You knew the chunked cap and the Accept tie-break diverged and shipped anyway" (F-7-06).

## Checked and fine (answered with a reason, alternative and trade-off)
- READ COMMITTED with a conditional UPDATE: DESIGN.md §4 explains why it is enough (row lock re-evaluates WHERE), names the accepted 404-versus-400 snapshot gap, and E1-B/C/D record rejected alternatives with reasons.
- Append-only trigger and balance-row duplication: E3 (statement-level trigger rejected, privilege separation rejected) and A11 and A14 give reasons; the test-only drift check is stated at README:180.
- Hash includes the API version: H10 gives a reason (a key stored by an old unversioned POST would replay the wrong body). The premise is questionable (F-7-10), but the logic is sound.
- NOT VALID migration: H9 records both alternatives with reasons.
- Guard filter before @Valid (M-13): H16 gives the reason (avoids the L21 shape). The behaviour is recorded in README:221.
- Mirror routes and details as a sub-resource: H6 gives a reason (stock and details in one request make a retry double stock or overwrite details) and a rejected alternative.
- Unversioned POSTs "not retry-safe" disclosure: README:83 and DESIGN.md:55, 86.
- CI parked: README:217 is honest and gives the owner action.
- ArchUnit: H15 and lessons.md give reasons; the allow-list rules for raw request paths are a reasonable answer.
- The board process: S9, OD-9/OD-10; dump reproduces byte for byte once the script can find Playwright (F-7-09).
- Walkthrough: every README "Try it" command matched its documented output on my stack.
- Front-end two-step create: FE34 states the failure modes (refresh loses initial stock; 412 after a lost create; SKU exists at 0). That is acceptable and honestly documented, apart from the key-not-persisted point in F-7-08.

## Counts
BLOCKER 0, MAJOR 6 (F-7-01 to F-7-06), MINOR 6 (F-7-07 to F-7-12; F-7-07 is the 412 text), NIT 1 (F-7-13), DECISION CHALLENGE 0.
Note: F-7-07 was written as MINOR but is the 412 text item; F-7-12 is test volume.
