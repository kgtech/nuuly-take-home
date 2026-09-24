# Agent prompts

A log of each AI session: the prompt, what came back, what I kept, what I threw out, and how I responded. Tool: Claude (Cowork). Decision IDs refer to [DECISIONS.md](DECISIONS.md).

---

## 1. Decision list before coding (2026-09-23)

**Prompt**
> Given this spec in Nuuly Assessment Readme, a 24-hour limit, and Java Spring Boot 4 + Postgres, list the decisions I must make before coding. Mark which ones block others, and separate spec gaps from design choices.

**Output summary**
- 10 spec gaps (G1–G10) and 11 design choices (D0–D10), each tagged with what it blocks, plus a suggested order: scope and runtime, then data shape, then concurrency and schema, then errors and tests.
- Flagged overselling under concurrency (G7/D4) as the main correctness risk.
- Flagged that openapi-generator's Boot 4 / Jackson 3 support is new and still has open issues, with sources.

**Accepted**
- The gap/design split and the blocking order.
- Concurrency as the central decision.

**Rejected / changed**
- Changed the scope of several items: added pagination (G9) and an Idempotency-Key (G8), both beyond the original spec.

**My response**
Answered G1–G6, G10, D0–D3 and D10 directly with my reasoning. Asked for research on G7/D4, and for a researched, interactive decision board for everything still open.

---

## 2. Research + interactive decision board (2026-09-23)

**Prompt**
> I know there will be open decisions. Please research the open decisions so I can make a decision. Using the decision list from the previous step, build an interactive decision dashboard. Do the research before building anything. […] Keep the recommendation collapsed until I choose an option. […] When I choose, check it against my other choices and the spec. Flag any conflict right away on both decisions. […] Exports: DECISIONS.md, CLAUDE.md, README assumptions. Priorities: tests must run the SQL that ships, stay within the time limit, and follow the spec's contract over industry convention.

**Output summary**
- Three parallel research agents checked about 70 sources and marked each claim verified or unverified. Unverified claims come with a test that would prove them.
- Research findings that changed the options:
  - Postgres re-checks a conditional UPDATE's WHERE clause after waiting, so atomic SQL can't oversell.
  - Hibernate 7.1 emits `FOR NO KEY UPDATE`, not `FOR UPDATE`.
  - Spring Framework 7 has `@Retryable` built in.
  - Overflow and CHECK violations raise the same Spring exception.
  - Gradle 9.1+ is required for Java 25.
  - The IETF Idempotency-Key draft has expired.
  - Opt-in paging with a Link header keeps the "all SKUs" contract.
- Added four decisions that my answers created: G11 (skuId charset and collation), G12 (int32 overflow), G13 (JSON strictness) and G14 (idempotency key semantics).
- The board ([ai/decision-board.html](ai/decision-board.html)) has conflict rules, an effort budget and generated exports.

**Accepted**
- The board, the new decisions G11–G14, and the research.
- The recommendation on 23 of 25 decisions.

**Rejected**
- G4-B (404 before quantity validation). I chose C, Spring's default.
- D3-B (JdbcClient). I kept Spring Data JPA.

**My response**
Answered all 25 decisions on the board. The board flagged a conflict between my first G4 answer (D: list every error) and G6 (fixed strings). I resolved it by switching G4 to C.

---

## 3. Conflict check on my answers (2026-09-24)

**Prompt**
> Responded to the prompts in the decision board.

**Output summary**
The rule checks found no conflicts, but two issues came up outside the rules:
- My G4 note (404 takes priority) contradicts my G4 choice (C, 400 wins).
- The chosen options add up to about 23h of estimated work against a 16h build budget.

**Accepted**
- Nothing changed.

**Rejected**
- Switching G4 to B.
- Building core features first and treating paging and idempotency as stretch goals.

**My response**
Kept G4-C and kept the full scope.

---

## 4. Generate repo files + parallel review (2026-09-24)

**Prompt**
> generate the repo files then review the decisions. Swarm and call out inconsistencies.

**Output summary**
- Generated DECISIONS.md, CLAUDE.md, the README assumptions and this log, using the board's own export logic.
- Four review agents ran in parallel: spec contract, internal consistency, stack feasibility, and priorities/testability. Their findings were merged into [ai/decision-review.md](ai/decision-review.md): 6 rules that would produce broken code, 4 contract conflicts, 6 disagreements between documents and 3 gaps against my priorities. The two biggest claims were checked against the source code and docs.
- Key finding: Spring Data `@Modifying` can't return `RETURNING` rows, so the D3 rule contradicts D4 and G12.

**Accepted**
- The review. I asked for more detail on five findings: idempotency rollback and concurrency, GET error scope, list paging errors, the G4 note, and details that came from option text.

**Rejected**
- Nothing yet. I didn't act on the other findings in this round.

**My response**
Asked for the five findings to be added to the decision board with more explanation.

---

## 5. Review follow-ups on the board (2026-09-24)

**Prompt**
> Give me more information on the following when adding to the board: G14 rollback and concurrent keys; G3 on GET endpoints; bad limit/after on the list; the G4 note; details that came from option text (Kotlin DSL, skuId pattern, limit 1–1000, 24h key expiry).

**Output summary**
- Nine new board cards (R1–R9). Each explains how the problem happens and gives options with sources:
  - R1: Spring rolls back on runtime exceptions (Spring docs), and Stripe only stores results once execution starts.
  - R2: the second insert waits on the unique index, then gets a 23505 unique violation, and the transaction is aborted.
  - R3: Spring's NoResourceFoundException returns 404 for unknown paths.
  - R4: AIP-158 and GitHub reduce an oversized page size to the maximum.
  - R6: Gradle 8.2 made the Kotlin DSL the default.
  - R8: page-size limits from Shopify, GitHub and Stripe.
- Added G10 option D, and "Apply" buttons so R3 and R5 can update G10 and G4 directly.
- Chosen follow-ups now refine the earlier rules in CLAUDE.md, so each rule appears once.

**Accepted**
- Every recommendation: R1-B, R2-A, R3-A (G10 → D), R4-A, R5-A (G4 note rewritten, choice C kept), R6-A, R7-A, R8-B, R9-B.

**Rejected**
- R1-A (success-only replay), R2-B/C/D, R3-B/C, R4-B/C, R5-B/C, R6-B, R7-B/C, R8-A/C, R9-A/C/D.

**My response**
- R2: flagged a Redis idempotency cache as the better long-term design and asked for it in the README. Claude recorded it under Future improvements, framed as a load and latency improvement with Postgres still the source of truth, because Redis's docs discourage relying on simple `SET NX` locks for correctness.
- R7: cited Square's SKU guidance (letters and digits only, start with a letter) and chose A. A still allows `.`, `_` and `-`, which the spec's own example `CW-XYCS-BM-01` needs.

---

## 6. Why JdbcClient instead of Spring Data? (2026-09-24)

**Prompt**
> Why do you keep recommending JDBC client instead of using Spring Data?

**Output summary**
- The recommendation covered only the three write statements that return a row: the add, the purchase and the idempotency claim.
- Spring Data's documented native-write path (`@Modifying`) can't return rows (spring-data-jpa #1708). The alternative, a plain native `@Query`, works but is undocumented (#2270, closed as "invalid").
- JdbcClient would have lived in a Spring Data repository fragment. Reads stay on JPA.
- Claude acknowledged that recommending JdbcClient for both D3 and S1 could read as a preference. With JPA chosen, S1-A is legitimate once a short spike proves it.

**Accepted**
- Switch the S1 recommendation to A (plain native `@Query` without `@Modifying`), with a Testcontainers spike first and B (JdbcClient fragment) as the fallback.

**Rejected**
- The earlier S1 recommendation (B first).

**My response**
Asked for S1 to be updated to spike A first. Later chose S1-B (JdbcClient repository fragment) on the board anyway. My reason: RETURNING statements should bypass Hibernate's query execution, and the @Query route loses the clearAutomatically/flushAutomatically safety.

---

## 7. Round 2 answers + consistency review (2026-09-24)

**Prompt**
> Updated. Review for new inconsistencies or errors in logic.

**Output summary**
- All 46 decisions are answered. Round-2 choices: S1-B, S2-C, S3-B (UUID keys), S4-B, S5-A, S6-A, S7-A, S8-B, S9-A, S10-A, S11-A, S12-A.
- Fixed in the export tooling (no decision changed):
  - The D3/S1 note was shown as a conflict; it is now informational.
  - The README listed the idempotency reuse assumption twice.
  - The G6 assumption was missing "Internal server error".
  - D8's combined option was listed as rejected.
  - The README intro version wording didn't match S10.
- Open issues are listed in ai/decision-review.md under "Round 3".

**Accepted**
- Adding T1–T3 and the scope cut (T6) to the board.

**Rejected**
- T1-A (take over expired keys). I chose D: never reuse an expired key. Redis or a cache would be the better long-term home for idempotency.

**My response**
Answered round 3: T1-D, T2-A (S2 note corrected), T3-B, T6-A.

---

## 8. Round 3 answers not saved; G8 confirmed (2026-09-24)

**Prompt**
> Done (with the exported DECISIONS.md pasted in)

**Output summary**
- The round-3 answers were only in the browser; the board's database never received them. Claude wrote them from the pasted export: T1-D, T2-A (plus the corrected S2 note), T3-B, T6-A.
- The pasted export showed G8 = D, while the saved board and my notes said A. The likely cause is pressing "Apply to G8" on T6-C and then switching T6 back to A.
- Board fixes:
  - An amber warning now shows when choices are saving only in the browser.
  - When a feature is cut, the cards that depend on it drop out of CLAUDE.md and the README, and DECISIONS.md marks them "Not in scope".
  - T1-D now also updates the G14 and S11 rules, so no rule still says expired keys are "treated as unseen".

**Accepted**
- G8 stays A: keep idempotency.

**Rejected**
- Dropping idempotency (G8-D).

**My response**
Confirmed G8-A.

---

## 9. T7: UUID key case (2026-09-24)

**Prompt**
> T7?

**Output summary**
- Postgres `uuid` columns treat upper- and lower-case keys as the same value. The UUID standard defines UUIDs as case-insensitive, so this is correct behaviour.
- It contrasts with the case-sensitive SKU rule (G1). Claude offered to add a README line explaining the difference.

**Accepted**
- Nothing.

**Rejected**
- Adding the README line.

**My response**
Do nothing.

---

## 10. T5: S9 rule (2026-09-24)

**Prompt**
> Fix T5, update the S9 rule

**Output summary**
- S9-A's rule now says DECISIONS.md is generated from the board and never hand-edited. The export lists the parts of a combined choice under "Included in the choice".
- S9-A's label changed from "Hand-edit DECISIONS.md…" to "List combined options as included…" to match. Its drawback about regeneration undoing hand edits was removed.
- The review has no open items left.

**Accepted**
- The rule and label change.

**Rejected**
- Nothing.

**My response**
_None yet._

---

## 11. Story-writing review: round 4 (2026-09-24)

**Prompt**
> (pasted a review from another session that was writing 8 stories) … do it

**Output summary**
- Verified the 9 findings. Three were real design gaps and became board cards:
  - U1: overflow outcome and the idempotency key.
  - U2: an unsupported Accept header on the GET endpoints.
  - U3: order of the key-format and skuId checks on purchase.
- Two findings came from the working repo missing files from the delivered package, not from the design.
- Two were Claude's errors and are now fixed:
  - The S2 note said "throw". It now says 404 goes through the shared helper.
  - DECISIONS.md showed stale labels for refined decisions. It now lists "Current rules" under each one.
- The last two were informational.

**Accepted**
- Adding U1–U3 and the two fixes.

**Rejected**
- Nothing yet.

**My response**
_U1–U3 still unanswered on the board._

---

## 12. Ledger, bigint and G2 (2026-09-24)

**Prompts**
> What about a ledger instead of directly modifying the inventory tracker? Wouldn't that account for overflow? … So we should use bigint for the quantity? … (pasted an external write-up on ledger math and BIGINT) … Lets use bigint/long so we don't have to defensively program against the issues on the backend calculation. Add a ledger card to the board

**Output summary**
- **Ledger and overflow:** a ledger moves overflow to the SUM and reopens the oversell race, because it has no row to lock. The production pattern is a balance row plus a ledger (Magento MSI).
- **Checked the external write-up on Postgres 16:**
  - Confirmed: int identity keys stop at 2,147,483,647, so ledger ids must be bigint.
  - Refuted: windowed SUM of signed deltas returns bigint and equals the balance.
  - Refuted: pgjdbc throws on an out-of-range int rather than truncating.
  - The Fowler citation was misattributed.
- **Board changes:**
  - G2 set to B (bigint/long).
  - New card V1: ledger (none / hybrid / ledger-only).
  - New card V2: request width and overflow guard under bigint. Choosing it rewrites G12, D4, S11 and the README.

**Accepted**
- bigint/long for quantities (G2-B).
- Adding the ledger card.

**Rejected**
- Keeping int32 (the earlier recommendation).

**My response**
Answered on the board: U1-A, U2-A (then C), U3-A, V1-C (ledger is the source of truth, citing Shopify's claim step), V2-A.

---

## 13. Round 6: notes vs choices, ledger as truth (2026-09-24)

**Prompt**
> Done.

**Output summary**
- The conflict check found three notes that disagreed with their choices:
  - U1: the note said overflow wasn't handled.
  - U2: the note said "GET only; POST → 400".
  - V1: the note said the ledger is the source of truth, but the choice was B, where the ledger is only an audit trail.
- Checked Shopify's article: it calls its inventory ledger the source of truth, but doesn't say whether that ledger stores balances or deltas.
- Added U2 option C and a new card W1 (concurrency when the ledger is the source of truth).

**Accepted**
- U1: keep A and reword the note.
- U2: GET ignores Accept, POST → 400 (option C).
- V1: C, the ledger is the source of truth (set on the board).

**Rejected**
- V1-B (hybrid).
- U2-A (ignore Accept everywhere).

**My response**
Chose W1-B (SERIALIZABLE + retry) and applied it to D4 (D).

---

## 14. Round 7: SERIALIZABLE follow-through (2026-09-24)

**Prompt**
> Updated

**Output summary**
- Checked W1-B against the rest of the design:
  - Spring's exception mapping for 40001 (two translators, two exception types, one shared parent).
  - Postgres docs: sequential scans take relation-level predicate locks, and failed transactions must be retried.
  - How a concurrent idempotency claim behaves under SERIALIZABLE (unverified).
- Fixed rules that W1-B hadn't refined: D5, G12, S11, V1, D4 and R2.
- Added card W2: retry budget and what happens when retries run out.

**Accepted**
- W2-A: 10 retries with jittered backoff; exhaustion → 500.

**Rejected**
- W2-B (default 3 retries).
- W2-C (switch to the row lock).

**My response**
Chose W2-A. All decisions are now answered.

## 15. GitHub issues rewritten for the ledger design (2026-09-24)

**Prompt**
> Now create a new github issues md (with the earlier `github-issues.md` attached)

**Output summary**
- Rewrote `ai/github-issues.md`: same eight stories and T6 build order, updated to the 57 current decisions.
- Story 2 now builds the `sku` and `inventory_ledger` tables, the SERIALIZABLE `INSERT … SELECT … WHERE` SUM checks, the W2 retry wrapper and the 9223372036854775807 guard, replacing the balance column, the 2147483647 guard and the 23514 test.
- Story 3 adds U2-C (GET ignores Accept; POST → 400 instead of 406).
- Story 4 adds the Overflow outcome, U3's check order, `long` responses and N ≤ 8 concurrency tests.
- Story 6 runs the idempotency claim inside the retried transaction and stores the overflow 400.
- Story 8 drops the file move (the layout already matches D0).
- Open verifications (the `@Retryable` attribute names, `ON CONFLICT` under SERIALIZABLE, springdoc `*/*`) are listed on the stories that settle them.

**Accepted**
- Story content for the ledger design: the `sku` + `inventory_ledger` schema, SERIALIZABLE SUM-checked writes, the W2 retry, the bigint guard, U1's Overflow outcome, U2-C's Accept handling, U3's check order and the N ≤ 8 concurrency tests.
- Story 8 without the file move, since the layout already matched D0.

**Rejected**
- The story order, which kept Compose in story 1 and so broke T6's build order. Round 8 (section 16) moved Compose to story 5.

**My response**
- Had a second agent review the stories against the decisions (section 16). Its findings led to the V1 SQL, the build-order rewrite and card X1.

## 16. Round 8: story review from a second agent (2026-09-24)

**Prompt**
> Pasted another agent's review of the GitHub stories (misalignments M1–M9), then: "Apply fixes + add M5 card", and "create a new file so that the zip export can replace the existing folder."

**Output summary**
- Checked each finding against the board source. All were real except M1 (already fixed by me). Found one more: build step 5 still said "app behind a profile".
- Fixed rule wording on the board: V1 now holds the full add and purchase SQL; G5, G11, S11, D5, D3 (`::bigint` casts), D9 (N ≤ 8), W1, T6 (about 45.5h), G12's question.
- Added card X1: where the retry and the SERIALIZABLE transaction boundary sit (conflicts with S1 and D3).
- Verified the Spring 7 `@Retryable` attribute names.
- Rewrote the build order in `ai/decision-review.md` and `ai/github-issues.md` to follow it (Compose moves to story 5; the section 15 version broke T6).
- Regenerated DECISIONS.md and CLAUDE.md.

**Accepted**
- Wording fixes and the X1 card.

**Rejected**
- (none)

**My response**
- Chose X1-B: `@Retryable` around a SERIALIZABLE `TransactionTemplate`. S1 and D3 now reference the template; issues story 2 and 6 updated.

## 17. Round 9: story review and follow-ups (2026-09-24)

**Prompt**
> Review these documents. Break out the work into 8 stories that can be stored in github issues. Highlight if there are any misalignments. (Then, over several turns: "scan again", followed by answers to the findings.)

**Output summary**
- First pass: eight stories and misalignments against the pre-ledger design. Second pass: the same eight stories against the ledger design, plus findings M1–M9 (handled in section 16).
- Third pass found four more gaps:
  - A POST with an unacceptable Accept changed stock and then returned 400.
  - The retry had no SQLState filter.
  - The request hash was undefined.
  - The idempotency table stored no Content-Type.
- It also found stale copies of DECISIONS.md, agent-prompts.md and the v2 issues file in `docs/`, and section 15's unfilled fields.
- Added cards Y1–Y4 to the board and republished it. Regenerated DECISIONS.md, CLAUDE.md and the README assumptions from the board by running its export code, which first reproduced the previous files byte for byte.
- Updated stories 2, 3 and 6 in `ai/github-issues.md` and added round 9 to `ai/decision-review.md`.

**Accepted**
- Y1-A: `produces = application/json` on both POST mappings.
- Y3-A: hash (operation, skuId, quantity) after parsing.
- Y4-A: store `content_type` with the idempotency response.
- Removing the stale `docs/` copies; filling in section 15.

**Rejected**
- The Spring Retry `exceptionExpression` version of the retry filter, which I proposed. It would add spring-retry and commons-lang3 and replace the Framework 7 `@Retryable` from W2 and X1. Y2-B records it.

**My response**
- Chose Y2-A: the same root-SQLState check, written as a Framework 7 `MethodRetryPredicate`.
