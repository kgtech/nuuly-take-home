# Decision queue: #23

Owner decisions for issue #23 (skuId path handling, invalid-ID GET without the database, Accept q=0, filter regression tests), exported from the run's decision-queue artifact (https://claude.ai/artifact/RfgGTYs1UeWEEjzSTZ83o1) at step 9. Answers are verbatim.

## Q23-01: Approve the #23 plan and new decision card C3

- Context: #23 step 1 (planner)
- Decision: Step 1 gate for issue #23. Approving creates the branch `feat/issue-23-raw-skuid-and-accept-q0`, adds board card C3 and posts a [Decision change] on #23.
- Finding or proposal: Plan: `.orchestrator/plan-issue-23.md`.

- **C-04 (`;` in skuId):** the controller passes the decoded raw path segment, `;` content kept, so `/inventory/ABC-1;lot=7` is checked as `ABC-1;lot=7` (create 400, GET/purchase 404, nothing written or stored). Today it writes to ABC-1.
- **C-05:** `find` checks the skuId before a read-only `TransactionTemplate`; `@Transactional` comes off `find`. GET `/inventory/-bad` with Postgres down: 500 after 30 s today, 404 immediately after.
- **C-34 (q=0):** a POST-only HandlerInterceptor applies RFC 9110 precedence; JSON at q=0 → the same 400 Y1 already uses. GET keeps ignoring Accept.
- **C-13:** filter regression rows.
- Board: new card C3 refining G11, S2, U2, Y1 (plan §6). README Assumptions lines updated. No new dependencies.

Open questions included in this approval (planner's recommendation in each): OQ1 `;` on literal segments (`/inventory;v=1/…`) stays ignored, as Spring does; OQ3 precedence by wildcard level only; OQ4 read-only TransactionTemplate after the check; OQ5 one C3 card; OQ6 `rawSkuId` as a private helper in the controller; OQ7 keep the unused `@PathVariable` for routing and docs. OQ2 is its own card (Q23-02).
- Rule at stake (G11): Check it in the service with a precompiled Pattern (SkuId.isValid)... controllers pass the raw path value.
- Rule at stake (U2): POST requests whose Accept excludes JSON get 400 "Invalid request" (HttpMediaTypeNotAcceptableException → 400 on POST).
- Link: [Issue #23](https://github.com/kgtech/nuuly-take-home/issues/23)
- Options:
  - A: Approve the plan and C3 with the recommendations above (touches: InventoryController, InventoryService, new JsonAcceptForPostInterceptor, 6 test classes + 1 new, README, board C3; cost: Normal issue run; risk: Low)
  - B: Approve, but reject any `;` under /inventory/** (OQ1 B / C3 option B) (touches: Adds a filter; changes routes that work today; cost: Similar; risk: Med)
- Recommendation A: Fixes all four findings with no new status codes and no change to routes that work today.
- **Owner's answer: Approved, option A** (no reason given)

## Q23-02: OQ2: checking q=0 with a HandlerInterceptor sits close to an option Y1 rejected

- Context: #23 step 1 (planner), conflict flagged
- Decision: The planner flagged a conflict with a recorded decision: Y1's rejected option B was rejected because it "re-implements media-type matching (q-values, wildcards) by hand".
- Finding or proposal: Spring's `produces` condition never reads q, so `Accept: application/json;q=0` passes handler lookup and the POST writes stock. The proposed interceptor re-implements only the q=0 precedence rule (~15 lines): among Accept ranges that include application/json, the most specific decides; q=0 there → throw the same `HttpMediaTypeNotAcceptableException` Y1's lookup path throws (→ 400). Type matching itself stays with Spring.
- Rule at stake (Y1): Both POST mappings declare produces = MediaType.APPLICATION_JSON_VALUE (and consumes = APPLICATION_JSON_VALUE), so an Accept that excludes JSON fails at handler lookup (HttpMediaTypeNotAcceptableException → 400 "Invalid request") before the controller, the idempotency claim or the ledger write runs.
- Rule at stake (Y1 rejected option B): re-implements media-type matching (q-values, wildcards) by hand
- Link: [Issue #23](https://github.com/kgtech/nuuly-take-home/issues/23)
- Options:
  - A: HandlerInterceptor after lookup, before argument resolution; same exception and 400 (touches: New JsonAcceptForPostInterceptor + unit test; C3 refines Y1; cost: ~15 lines + tests; risk: Low)
  - B: Check at the top of create and purchase in the controller (touches: InventoryController; Y1's "before the controller" wording no longer holds; cost: Same; risk: Low)
  - C: Custom global ContentNegotiationStrategy (touches: Changes negotiation for actuator and springdoc too (S6); cost: More; risk: High)
  - D: Don't fix C-34; document q=0 as a known edge (touches: README only; cost: None; risk: Med)
- Recommendation A: It keeps Y1's single 400 path and its "before the controller" guarantee, and hand-codes only precedence, not type matching, so the reason Y1 rejected option B mostly does not apply. You already chose to fix C-34 at triage, so D would reverse that.
- **Owner's answer: Approved, option A** (no reason given)

## Q23-03: Test author departures from the #23 plan

- Context: #23 step 2 (test author)
- Decision: The test author changed a test name and added rows the plan didn't list. Agent changes are proposals until you approve them; they are already pushed in 8d2dc25.
- Finding or proposal: - Test named `JsonAcceptForPostInterceptorTest#nonPostIsNeverChecked` (GET, HEAD, OPTIONS) instead of the plan's `#getIsNeverChecked` (GET only), because plan §5 says anything other than POST passes.
- Extra rows beyond plan §8: `ABC-1;;` and `ABC-1;/purchase` (empty path parameters, which Spring can't see as matrix variables), a percent-decoded `AB%43-1;x=%41` → `ABC-1;x=A`, and the literal `…/purchase;x` row in MockMvc.
- Not written: the optional DB-down GET test (plan U4); that check stays in the compose smoke.
- Link: [Commit 8d2dc25](https://github.com/kgtech/nuuly-take-home/commit/8d2dc25)
- Options:
  - A: Keep as pushed (touches: none; cost: None; risk: Low)
  - B: Rename to the plan's name and drop the extra rows (touches: Tests only; cost: One test-author pass; risk: Low)
- Recommendation A: The name matches the behaviour it checks, and the extra rows cover edge cases plan §5 already specifies.
- **Owner's answer: Approved, option B** (no reason given)

## Q23-04: Implementer follow-ups: a tie-rule test, Z2's class list, and the mutation baseline

- Context: #23 step 3 (implementer)
- Decision: The implementer finished green but left three items it didn't act on; one changes tests, one would change the board.
- Finding or proposal: 1. **Tie rule untested.** When two Accept ranges are equally specific (e.g. `application/json, application/json;q=0`), the highest q decides (your OQ3 A). No test sends that, so the implementer rewrote the check as a stream to leave no surviving mutant; a test row would pin the rule itself: `Arguments.of(List.of("application/json, application/json;q=0"), false)`.
2. **Z2's web class list** doesn't name the new `JsonAcceptForPostInterceptor` (nor C1's valve/config classes, nor `StoredResponses`). Issue #28 (C-18) already covers refreshing that list.
3. **Mutation baseline moved.** The critique's 113/129 and 123/128 were at 211bd95; after C1 and C2 main is 109/126 and 119/125 on those scopes. This branch: 112/130 (+ the new interceptor 12/12) and 122/129. No new survivors except `setReadOnly` in the constructor, the same kind as the known cached-context survivors.

**New evidence from the round-1 review:** the tie case is not just untested, it is wrong today: `Accept: application/json;q=0, application/json;charset=utf-8` returns 200 and writes stock, and so do two Accept header lines `application/json;q=0` + `application/json`. Your OQ3 A said the highest q among equally specific ranges decides; with parameters ignored in ranking, both ranges tie and the non-zero q should win — so 200 is actually what OQ3 A specifies. The row pins that intended behaviour.
- Link: [PR #34](https://github.com/kgtech/nuuly-take-home/pull/34)
- Options:
  - A: Add the tie-rule row (in the review round's test pass); leave Z2's list to #28; compare mutation scores against main from now on (touches: JsonAcceptForPostInterceptorTest; run logs; cost: One test row; risk: Low)
  - B: Add the row and also update Z2 here (board change) (touches: Board C3 refines Z2; DECISIONS.md/CLAUDE.md; cost: Board change; risk: Low)
  - C: No test row; leave everything (touches: none; cost: None; risk: Low)
- Recommendation A: The row pins your precedence decision directly instead of relying on how the code is written. Z2's list already has an issue (#28) that fixes all its gaps at once. Comparing against the moving main is the fair gate.
- **Owner's answer: Approved, option A** (no reason given)

## Q23-05: R1-6 decision challenge: U3's POST order doesn't list the Accept gates

- Context: #23 step 4 (review round 1), PR #34
- Decision: The round-1 reviewer raised a DECISION CHALLENGE; the fixer replies with your decision.
- Finding or proposal: U3 says the POST order is body validation → Idempotency-Key format → skuId pattern → claim and write. In fact the Accept checks run first: Y1's handler lookup (Accept excluding JSON) and now C3's interceptor (JSON at q=0), both before body parsing. Clients get the same 400 either way, so this is wording, not behaviour.
- Rule at stake (U3): Order on both POSTs: body validation (@Valid, controller) → Idempotency-Key format (@Idempotent interceptor, 400) → skuId pattern (service check; run by the interceptor before the claim when a key is present; create 400, purchase 404) → claim and stock write.
- Link: [Round-1 review](https://github.com/kgtech/nuuly-take-home/pull/34#pullrequestreview-5331410719)
- Options:
  - A: Refine U3 via C3: "Accept (Y1 lookup, C3 interceptor) → body validation → …" (touches: Board: C3 REFINES U3; CLAUDE.md/DECISIONS.md regenerated; cost: Board change only; risk: Low)
  - B: Leave U3 as is (same 400 either way) (touches: none; cost: None; risk: Low)
- Recommendation A: U3 exists to tell readers what runs first; the Accept gates now run first, and the next person adding a gate needs the real order. It's a one-line board change in the same PR.
- **Owner's answer: Approved, option B** (no reason given)

## Q23-06: Pre-existing problems that appear only when a servlet path is configured

- Context: #23 step 6 (review round 2), noticed but not posted
- Decision: The round-2 reviewer probed `spring.mvc.servlet.path=/api` to check this PR's `rawSkuId` (it is correct) and found older problems it did not post, because they predate this PR and the setting isn't used. Nothing here blocks #34.
- Finding or proposal: Under `spring.mvc.servlet.path=/api`:
- `JsonAcceptForGetFilter` and `InventoryErrorAdvice` strip only the context path, so the GET Accept filter doesn't fire: `GET /api/inventory/SP-1` with `Accept: application/xml` → 406.
- `nextLink` leaves out `/api`: `Link: <http://localhost:8080/inventory?limit=1&after=ABC-1>`.
- `POST /api;x/inventory/SP-1` → a JSON 500 from Spring's own path parsing.

Also noted: `Accept: application/*+json;q=0, application/*` is accepted (both rank as wildcard subtypes; highest q wins). `*+json` isn't valid RFC 9110 range syntax.
- Rule at stake (S6/C1): ...a request Tomcat rejects before routing is text/plain on every path...
- Link: [Round-2 review](https://github.com/kgtech/nuuly-take-home/pull/34#pullrequestreview-5331563996)
- Options:
  - A: Document in README that a servlet path is not supported, like the context-path and proxy notes (Q22-06, Q22-10) (touches: README (one clause); fold into a later issue's docs pass; cost: Minimal; risk: Low)
  - B: File a follow-up issue to support a servlet path (filter, advice, Link) (touches: New issue, later; cost: Later pipeline run; risk: Low)
  - C: Leave it; nothing configures a servlet path (touches: none; cost: None; risk: Low)
- Recommendation A: It matches how you handled the other deployment-dependent Link and path issues: say what isn't supported instead of building for a configuration nobody uses.
- **Owner's answer: Approved, option A** (no reason given)

## Q23-07: C3 rule 3 doesn't state the tie rule or that suffix ranges count

- Context: #23 step 8 (verifier)
- Decision: The verifier found CLAUDE.md's C3 rule 3 less precise than the code after round 1. It doesn't contradict the code; it leaves out two things you decided or approved.
- Finding or proposal: C3 rule 3 says "the most specific Accept range that matches application/json decides". The code (and the interceptor Javadoc and PR body) also: matches ranges with `isCompatibleWith`, the same comparison handler lookup uses, so suffix ranges like `application/*+json` count (R1-2 fix); and among equally specific ranges takes the highest q (your Q23-04 A).
- Rule at stake (C3 rule 3): On POST, the most specific Accept range that matches application/json decides (RFC 9110); q=0 means JSON is refused...
- Link: [PR #34](https://github.com/kgtech/nuuly-take-home/pull/34)
- Options:
  - A: Amend C3 rule 3: "...the most specific Accept range compatible with application/json (isCompatibleWith, as handler lookup; suffix ranges such as application/*+json count) decides; among equally specific ranges the highest q counts..." (touches: Board C3 text; CLAUDE.md regenerated; one docs commit on the branch; cost: Board change only; risk: Low)
  - B: Leave the rule as is; the Javadoc and PR body carry the detail (touches: none; cost: None; risk: Low)
- Recommendation A: CLAUDE.md is what future agents follow; the tie rule is your decision and the isCompatibleWith choice fixed a real bug, so both belong in the rule. It's a docs-only commit before merge.
- **Owner's answer: Approved, option A** (no reason given)
