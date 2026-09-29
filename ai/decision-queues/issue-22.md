# Decision queue: #22

Owner decisions for issue #22 (default page limit, Link encoding, OpenAPI constraints), exported from the run's decision-queue artifact (https://claude.ai/artifact/3E8MThxYUTES3h7WMCFxWv) at step 9. Answers are verbatim. Q22-01 was backfilled from chat when the run switched to the updated templates; Q22-02's answer was given in chat and copied into the card. There is no Q22-12.

## Q22-01: Approve the #22 plan and its open questions

- Context: Backfilled from chat
- Decision: Step 1 gate for issue #22.
- Finding or proposal: Plan: `.orchestrator/plan-issue-22.md` (C2 board card; default page 250; routed-path Link; OpenAPI skuId pattern/minLength/maxLength, minimum 0, default limit). OQ1 Link always carries limit; OQ2 `@Schema(minimum="0")` on domain InventoryItem; OQ3 include minLength 1; OQ4 C-08/C-09 rules in C2; OQ5 X-Forwarded smoke only; OQ6 leave G9 note as history.
- Link: [Issue #22](https://github.com/kgtech/nuuly-take-home/issues/22)
- Options:
  - A: Approve with the planner's recommendations
- Recommendation A: Approve.
- **Owner's answer: Approved, option A**: "Yes"

## Q22-02: `BASE_PATH` is package-private and the controller static-imports its own constant

- Context: #22 step 3 (implementer), PR #33
- Decision: The implementer departed from plan §4 (which said `private static final String BASE_PATH`). An agent's change is a proposal until you approve it; the fix round is next.
- Finding or proposal: A class-level annotation is outside the class body, so the bare name `BASE_PATH` is not in scope in `@RequestMapping(BASE_PATH)`; and Java cannot import a `private` member, even into its own class. To use the bare name (Z2: no qualified names in annotations) the implementer made `BASE_PATH` package-private and added `import static com.kgtech.inventoryapi.inventory.web.InventoryController.BASE_PATH`. It compiles; the self-import is unusual and widens the constant's visibility. `@RequestMapping(InventoryController.BASE_PATH)` would compile with a private constant but is a qualified name in an annotation.
- Rule at stake (Z2): Controllers static-import constants and import nested types, so method bodies and annotations use no qualified names (e.g. case Ok ok ->, APPLICATION_JSON_VALUE).
- Rule at stake (D10): Classes are package-private unless another package uses them...
- Link: [PR #33](https://github.com/kgtech/nuuly-take-home/pull/33)
- Link: [Commit d529f53](https://github.com/kgtech/nuuly-take-home/pull/33/commits/d529f53)
- Options:
  - A: Keep: package-private `BASE_PATH` + static self-import (touches: InventoryController.java; cost: None (already done); risk: Low)
  - B: Use `@RequestMapping("/inventory")` literal and keep `BASE_PATH` private for `nextLink` (touches: InventoryController.java; cost: One small commit; risk: Low)
  - C: `@RequestMapping(InventoryController.BASE_PATH)` (qualified name in an annotation) (touches: InventoryController.java; conflicts with Z2 wording; cost: One small commit; risk: Low)
- Recommendation B: Style call; either is fine. B is conventional Spring and keeps the constant private. Its cost is `/inventory` written twice, which routing tests and Link tests both pin, so drift fails a test. A keeps one source but relies on an import most readers won't expect. (Corrected: an earlier version of this card wrongly said Java normally allows the private constant here.)
- **Owner's answer: Approved, option B**: "B"

## Q22-03: Shared `SKU_ID_DESCRIPTION` constant for the three skuId `@Parameter` descriptions

- Context: #22 step 3 (implementer), PR #33
- Decision: Departure from plan §5, which inlined the description three times. Text is unchanged.
- Finding or proposal: Adds `private static final String SKU_ID_DESCRIPTION` in InventoryController; the three `@Parameter(description = SKU_ID_DESCRIPTION, ...)` share it.
- Link: [PR #33](https://github.com/kgtech/nuuly-take-home/pull/33)
- Link: [Commit d529f53](https://github.com/kgtech/nuuly-take-home/pull/33/commits/d529f53)
- Options:
  - A: Keep the constant (touches: InventoryController.java; cost: None; risk: Low)
  - B: Inline the text three times, as planned (touches: InventoryController.java; cost: Small commit; risk: Low)
- Recommendation A: Same text, one source; matches critique finding C-37's direction (no duplicated annotation literals).
- **Owner's answer: Approved, option A**: "If the constants work we need to have a centralized location for all constants. Otherwise this should be a string literal. Constants need to be together for readability for human readers."

## Q22-04: `truncateAtNul` null check moved into `list` to kill a new surviving mutant

- Context: #22 step 3, reopened with an answer to your question
- Decision: Departure from plan §5's `list` shape; behaviour is unchanged.
- Finding or proposal: **Your question: why commit a change with unchanged behaviour?** With the new default page, `list` treats a missing `after` and an empty one the same way (both start from the first SKU). In the plan's shape, `truncateAtNul(null)` returned `null` and `list` then turned `null` into the first-SKU cursor, so `truncateAtNul`'s null branch became dead code: no input can make it matter. PIT showed this as a surviving mutant. The implementer moved the null check into `list` so the dead branch is gone. There is no behavioural reason for it, only removing a branch that can't matter; since you said the mutation score is not the concern (Q22-05), reverting is equally valid.
- Link: [PR #33](https://github.com/kgtech/nuuly-take-home/pull/33)
- Link: [Commit d529f53](https://github.com/kgtech/nuuly-take-home/pull/33/commits/d529f53)
- Options:
  - A: Keep (touches: InventoryService.java; cost: None; risk: Low)
  - B: Revert to the plan's shape and accept one surviving mutant (touches: InventoryService.java; cost: Small commit; mutation score drops; risk: Low)
- Recommendation B: Revert to the plan's shape. It keeps the diff to what C2 requires, and the dead branch is harmless. Keep A only if you prefer removing dead code over a minimal diff.
- Earlier answer (Something else): "The behavior is unchanged so why commit this change?" (card reopened with an answer to the question)
- **Owner's answer: Approved, option B** (no reason given)

## Q22-05: Mutation gate: killed counts fell because deleted code took its mutants with it

- Context: #22 step 3 (implementer), PR #33
- Decision: Plan/issue AC: 'mutation scores for the scoped classes not lower'. Scores are marginally lower as a percentage.
- Finding or proposal: No-DB: 108/124 (87.1%) vs baseline 113/129 (87.6%). Testcontainers: 118/123 (95.9%) vs 123/128 (96.1%). The 16 and 5 unkilled mutants are the same set as at baseline; the drop comes from deleting the unbounded branches (5 killed mutants removed). No new survivors.
- Rule at stake (#22 AC): Suite green; mutation scores not lower.
- Link: [PR #33](https://github.com/kgtech/nuuly-take-home/pull/33)
- Link: [Commit d529f53](https://github.com/kgtech/nuuly-take-home/pull/33/commits/d529f53)
- Options:
  - A: Accept: same unkilled set, no new survivors (touches: Run log only; cost: None; risk: Low)
  - B: Require the percentage to match or exceed baseline (touches: Tests; possibly new mutation-killing tests; cost: Extra test work; risk: Low)
- Recommendation A: The gate exists to stop weaker tests; the unkilled set is identical, so the tests are not weaker. The percentage moved only because killed mutants were deleted with dead code.
- **Owner's answer: Something else**: "The issue isn't the weaker tests its the overlapping tests."

## Q22-06: R1-7 decision challenge: the next-page Link is absolute, built from the Host header

- Context: #22 step 4 (review round 1), PR #33
- Decision: The round-1 reviewer raised a DECISION CHALLENGE; the fixer replies with your decision. This behaviour predates #22 (G9 era) and C2 rule 2 keeps it.
- Finding or proposal: The Link is `http://<Host>/inventory?...`. X-Forwarded-* is ignored (no forward-headers strategy), so behind a TLS-terminating proxy a client gets an unreachable `http://internal-host:port` Link, and any Host a client sends is echoed back into the response (the smoke confirmed `Host: api.example.com:9999` → Link on that host), a cache-poisoning vector if a shared cache sits in front.
- Rule at stake (C2 rule 2): The next-page Link is built from the request's scheme, host, port and context path plus the routed path /inventory, never from the raw request URI...
- Rule at stake (#22 OQ5 (owner)): Verify X-Forwarded handling only in the smoke, not pinned in a test (the owner may later configure server.forward-headers-strategy).
- Link: [Review 5327329135](https://github.com/kgtech/nuuly-take-home/pull/33#pullrequestreview-5327329135)
- Options:
  - A: Relative Link: `</inventory?limit=…&after=…>; rel="next"` (RFC 8288 resolves it against the request URL) (touches: InventoryController.nextLink; InventoryListPagingTest (linkReflectsRequestHost, linkKeepsContextPath expectations), InventoryPagingIntegrationTest/InventoryListMalformedQueryIntegrationTest Link assertions; C2 rule 2 board text; README paging example; cost: Small code change, ~6 test expectation updates, board change; risk: Low)
  - B: Keep absolute; set `server.forward-headers-strategy: native` (Tomcat RemoteIpValve, trusts internal proxy IPs only) (touches: application.yaml; a smoke line; C2 rule 2 / README; cost: One config line, board change; risk: Med (still echoes Host when no proxy))
  - C: Keep as is; document the proxy/Host limitation in README and defer (touches: README only; cost: Minimal; risk: Med)
  - D: Keep absolute, but also fix R1-1 (context path) — orthogonal; choose with A/B/C (touches: InventoryController; cost: Small; risk: Low)
- Recommendation A: A relative Link removes both the proxy problem and the Host echo, and also makes R1-1 (context-path double encoding) moot if built from the servlet context path. It is the smallest change that makes the Link correct in every deployment. It does change behaviour clients see today (absolute → relative URL) and needs a board change to C2 rule 2.
- **Owner's answer: Approved, option C** (no reason given)

## Q22-07: GET /inventory 200 description still says "List of all inventory items"

- Context: #22 step 4 (review round 1), PR #33
- Decision: The reviewer noticed this but didn't post it; the new rules say unacted observations come to you.
- Finding or proposal: The 200 response description is the spec's wording. With the default page of 250 it reads loosely; the operation summary stays "List all inventory" by design (ApiDocsTest pins it) and the new description explains the paging.
- Rule at stake (C2 rule 3): ...GET /inventory's description and limit parameter (default 250) document the default page.
- Link: [Review 5327329135](https://github.com/kgtech/nuuly-take-home/pull/33#pullrequestreview-5327329135)
- Options:
  - A: Keep the spec's wording (touches: none; cost: None; risk: Low)
  - B: Change to e.g. "One page of inventory items (at most 250)" (touches: InventoryController @ApiResponse; openapi.yaml; ApiDocsTest if it pins the text; cost: Small; risk: Low)
- Recommendation A: The spec's text is intentional and the operation description already states the page size.
- **Owner's answer: Approved, option B**: "The sepcs text is intentional but we've experienced during testing the issue when we have a large dataset and try to return all data. This is a tradeoff that needs to be made in order to prevent 500s when getting a large list of inventory."

## Q22-08: Where do constants live? (follows your Q22-03 answer)

- Context: #22, follows Q22-02 and Q22-03
- Decision: You approved the shared description constant on the condition that constants live in one place for readability, otherwise use literals. This decides where, and it changes Q22-02: with the constants in their own class, `@RequestMapping(BASE_PATH)` works through a normal static import, so the literal from Q22-02 B would no longer be needed.
- Finding or proposal: Today the web layer has `/inventory` in three places: `InventoryController.BASE_PATH`, `JsonAcceptForGetFilter.BASE` and (after Q22-02 B) a literal in `@RequestMapping`. Query parameter names `limit`/`after` and the OpenAPI description strings are private constants in the controller. `HttpConstants` (package `web`) holds only the `Idempotency-Key` header name. Other constants are domain rules kept next to their logic: SQL next to its repository (S1), error texts in `TextErrors` (G6/S5), the skuId pattern in `SkuId` (G11), page sizes in `InventoryService` (R8/C2).
- Rule at stake (Z2): Header names are never string literals in code: they come from com.kgtech.inventoryapi.web.HttpConstants (e.g. IDEMPOTENCY_KEY) or Spring's HttpHeaders/MediaType constants, including in springdoc annotations.
- Rule at stake (Z2): ...a web package (com.kgtech.inventoryapi.inventory.web: InventoryController, InventoryErrorAdvice, JsonAcceptForGetFilter, TextErrors, InventoryQuantity, OutcomeResponses)...
- Link: [PR #33](https://github.com/kgtech/nuuly-take-home/pull/33)
- Options:
  - A: One API constants class for the web layer (e.g. `inventory.web.InventoryApi`): the base path, query parameter names and the OpenAPI description strings. Controller, filter and Link builder static-import it; `@RequestMapping(BASE_PATH)` works. Domain constants (SQL, error texts, skuId pattern, page sizes) stay beside their logic. (touches: New InventoryApi.java; InventoryController; JsonAcceptForGetFilter; board: Z2 class list + a rule; supersedes Q22-02 B; cost: Small commit + board change; risk: Low)
  - B: One constants class for the whole application, including SQL, error texts, the skuId pattern and page sizes (touches: Most main classes; public constants across packages; conflicts with D10 (package-private) and S1 (SQL in the repository fragment); cost: Large, touches merged code; risk: Med)
  - C: No shared constants class: inline single-use strings as literals; keep `BASE_PATH` private only if used twice (touches: InventoryController; Q22-02 B stands; cost: Small; risk: Low)
- Recommendation A: It puts every string a reader of the HTTP contract looks for in one file, removes the three copies of `/inventory`, and makes Q22-02's literal unnecessary, without dragging domain rules (SQL, error texts) out of the classes that own them. It needs a board change: Z2's web class list and a rule like "API paths, query parameter names and OpenAPI texts live in InventoryApi".
- **Owner's answer: Approved, option A** (no reason given)

## Q22-09: What to do about the overlapping tests (follows your Q22-05 answer)

- Context: #22, follows Q22-05
- Decision: You said the concern isn't weaker tests but overlapping tests. I read that as: accept the mutation percentage (it moved only because deleted code took its mutants) and act on the overlap findings from the round-1 review in this PR. Confirm the scope.
- Finding or proposal: Round-1 overlap findings on PR #33: R1-2 `linkWithDefaultLimit` runs the same code as `linkUsesNormalizedLimit`; R1-3 the MockMvc `/%69nventory` Link test repeats the real-server test; R1-4 two service tests differ only in input; R1-5 `ignoredLimitUsesDefaultPage` seeds 251 rows for 4 values the service test already covers; R1-6 two empty-table tests differ only in the URI. Suite-wide overlaps from the critique (C-20 to C-28, C-33) are already issues #29 and #30.
- Rule at stake (#22 AC): Suite green; mutation scores not lower.
- Link: [Review 5327329135](https://github.com/kgtech/nuuly-take-home/pull/33#pullrequestreview-5327329135)
- Options:
  - A: Accept the percentage; fix R1-2 to R1-6 in this PR (touches: InventoryListPagingTest, InventoryServiceReadTest, InventoryPagingIntegrationTest; cost: One test-author pass; risk: Low)
  - B: Accept the percentage; fix only R1-2 and R1-3 (outright duplicates); leave the NIT merges to #30 (touches: InventoryListPagingTest; cost: Smaller; risk: Low)
  - C: Accept the percentage; leave all overlap findings to issue #30 (touches: none now; cost: None now; risk: Low)
- Recommendation A: They are all in files this PR already changes and were introduced or touched by it, so fixing them now avoids new overlap landing on main. None removes the only test for a criterion.
- **Owner's answer: Approved, option A** (no reason given)

## Q22-10: R1-1: a configured context path could be double-encoded in the Link

- Context: #22 step 5 (fixer), PR #33
- Decision: The fixer judged R1-1 valid but can't fix it without a new test, so it is waiting on you: fix now, or defer like the other deployment-dependent Link issue (Q22-06).
- Finding or proposal: `fromCurrentContextPath()` takes the raw `request.getContextPath()`, and `.encode()` would turn a `%` in it into `%25`. With `server.servlet.context-path=/app`, a request to `/%61pp/inventory` would get a Link to `/%2561pp/...` (404). No context path is configured, so it can't happen today. A fix is one line (decode the context path before `.path(BASE_PATH)`) plus a MockMvc or real-server test with a percent-encoded context path.
- Rule at stake (C2 rule 2): The next-page Link is built from the request's scheme, host, port and context path plus the routed path /inventory, never from the raw request URI...
- Link: [R1-1 thread](https://github.com/kgtech/nuuly-take-home/pull/33#discussion_r4114432939)
- Options:
  - A: Fix now: decode the context path; add a test for an encoded context path (touches: InventoryController (Link builder); InventoryListPagingTest or a real-server test; cost: One test-author pass + one fixer pass; risk: Low)
  - B: Defer: record as a latent MINOR next to the proxy/Host note in README (touches: README (one clause); PR thread reply; cost: Minimal; risk: Low)
- Recommendation B: It needs a context path the app doesn't configure, and it belongs with the deployment concerns you already deferred in Q22-06 (proxy and Host). If a context path is ever introduced, fix both together.
- **Owner's answer: Approved, option B** (no reason given)

## Q22-11: InventoryApi scope: C2 says "the OpenAPI description texts", but single-use texts stayed inline

- Context: #22 step 5 (fixer), PR #33
- Decision: The fixer moved reused strings and the descriptions it was asked to move into `InventoryApi`. The spec-verbatim operation summaries and the other operations' response descriptions are still literals in the controller. That matches your rule ("centralize constants, otherwise use a string literal"), but not C2's wording, which says all OpenAPI description texts live in `InventoryApi`.
- Finding or proposal: `InventoryApi` now holds BASE_PATH, LIMIT, AFTER, SKU_ID_DESCRIPTION, IDEMPOTENCY_KEY_DESCRIPTION (was duplicated on both POSTs), LIST_DESCRIPTION, LIST_OK_DESCRIPTION, LIST_INVALID_DESCRIPTION, LINK_DESCRIPTION, LIMIT_DESCRIPTION, AFTER_DESCRIPTION. Inline: the four `summary` values ("List all inventory", etc., pinned verbatim by ApiDocsTest) and the item/create/purchase response descriptions.
- Rule at stake (C2 (new rule)): The web layer's API strings live together in inventory.web.InventoryApi: the base path /inventory, the query parameter names and the OpenAPI description texts...
- Rule at stake (Your Q22-03 answer): If the constants work we need to have a centralized location for all constants. Otherwise this should be a string literal.
- Link: [PR #33](https://github.com/kgtech/nuuly-take-home/pull/33)
- Options:
  - A: Move every OpenAPI text (summaries and all response descriptions) into InventoryApi, matching C2 as written (touches: InventoryApi, InventoryController; openapi.yaml unchanged; cost: One fixer pass; risk: Low)
  - B: Keep as is and amend C2's wording: InventoryApi holds the base path, parameter names and any API string used more than once or shared across operations; single-use texts stay inline as literals (touches: Board change (C2 wording); no code; cost: Board change only; risk: Low)
- Recommendation A: Your answer asks for constants to be together for human readers, and C2 already says all OpenAPI texts. Moving the remaining literals makes the controller read as mappings plus annotations that point at one file, with no judgement call per string about "used once or twice".
- **Owner's answer: Approved, option A** (no reason given)

## Q22-13: R2-3: are the mapping path templates "API paths" that belong in InventoryApi?

- Context: #22 step 6 (review round 2), PR #33
- Decision: Round-2 DECISION CHALLENGE. The two rules that came out of your constants decision disagree about path templates; the fixer (step 7) applies your answer.
- Finding or proposal: `@GetMapping("/{skuId}")`, `@PostMapping("/{skuId}")` and `@PostMapping("/{skuId}/purchase")` still use literals in InventoryController. Z2 (as refined) says InventoryApi holds "API paths"; C2's new rule says it holds "the base path /inventory". `/{skuId}` is used twice (GET and create).
- Rule at stake (Z2 (refined by C2)): ...OutcomeResponses, InventoryApi (API paths, parameter names and OpenAPI texts, C2))
- Rule at stake (C2 rule 5): The web layer's API strings live together in inventory.web.InventoryApi: the base path /inventory, the query parameter names and the OpenAPI description texts...
- Rule at stake (Your Q22-03 answer): If the constants work we need to have a centralized location for all constants. Otherwise this should be a string literal. Constants need to be together for readability for human readers.
- Link: [Round-2 review](https://github.com/kgtech/nuuly-take-home/pull/33#pullrequestreview-5329343003)
- Options:
  - A: Move the path templates into InventoryApi (e.g. ITEM_PATH, PURCHASE_PATH) and reword C2 rule 5 to "the API paths" (touches: InventoryApi, InventoryController; board change to C2 (DECISIONS.md, CLAUDE.md); openapi.yaml unchanged; cost: Small commit + board change; risk: Low)
  - B: Keep the templates as literals next to their handlers and reword Z2 to "the base path" (touches: Board change to the Z2 refinement only; cost: Board change; risk: Low)
- Recommendation A: It matches your "constants together" answer and removes the duplicated `/{skuId}`; a reader then sees every URL the API serves in one file.
- **Owner's answer: Approved, option A** (no reason given)
