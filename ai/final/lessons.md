# Lessons for the final build

This file merges what build v1 (on `main`) and build v2 (on branch `v2`) learned into one list. Each lesson names its evidence and where [`PROMPT.md`](PROMPT.md) enforces it.

Where the evidence comes from:
- **Build v1:** [`agent-prompts.md`](../../agent-prompts.md), [`ai/decision-review.md`](../decision-review.md), and the codebase critique. Its prompt is [`ai/codebase-critique.md`](../codebase-critique.md); its findings, C-01 to C-39, were posted on the critique PR and grouped into issues #21–#30.
- **Build v2:** its `lessons.md`, `DESIGN-V2.md`, `DEVIATIONS.md`, its run log and its critique. These move to `ai/v2/` at setup.
- **IDs:** K-, R-, S- and T- IDs are build v2's `lessons.md` entries; A- IDs are its `DECISIONS-ADDED.md` entries.

**Guard** means the lesson must become a test or an ArchUnit rule, and the run writes the test's name into the last column.

## Process

| # | Lesson | Evidence | Enforced by |
|---|---|---|---|
| L1 | Gate on the plan before code. | Overrides at the plan gate cost 0 rework commits; changes after the verifier cost 1–8 commits each. "Decide first, check after" happened in 4 of 9 v1 runs (K-10, #25). | Plan gate; OD-8 |
| L2 | Silence is not approval. | v1 K-13. v1 session 8: a board answer saved only in the browser was lost, and "Apply to G8" silently flipped G8. | Plan gate |
| L3 | Preflight what the environment allows before relying on it. | v1: port 8080 was taken, and the OrbStack socket and worktree hooks blocked work (K-01). v2: CI never ran because the token lacked the workflow scope, and a permission check blocked self-merge for about 35 minutes. | Preflight |
| L4 | Verify framework and runtime claims before planning on them; prove the uncertain ones with a test first. | Unchecked claims were wrong in 6 of 9 v1 runs; for example, 55P03 arrives as `UncategorizedSQLException`. v2: Jackson 3 answers 400 for a missing primitive, and Spring sent an automatic 304 on the v2 GET. | Rules: verify claims first; `board-cards.md` marks claims verified or unverified |
| L5 | One role owns the tests, and red tests come first. | v1: 0 of 37 implementer commits touched `src/test` (K-03). v2 wrote its storage tests in the same pass as the code, and the first run had 24 failures. | Rules: tests first |
| L6 | Push only green commits, and every commit must build on its own. | v1 had 4 red commits in 3 runs (R-07). v2 pushed red commit e8bb70c when the session hit its usage limit. | Rules: green |
| L7 | Add CI before the first code PR. | v1: 0 of 13 merged PRs had checks. v2 parked its workflow and never ran it. | Build order step 2; preflight |
| L8 | Give every role a "the plan is wrong" channel, and stop and propose instead of working around it. | v1 K-04 and R-01: all 5 decision challenges found in the critique had never been raised in a PR review. | Rules: review (DECISION CHALLENGE); `deviations.md` |
| L9 | In round 2, review the round-1 fix commits for regressions. | v1 K-07: 3 of 4 round-2 findings were regressions. v2's details run: fix PRs #78 and #79 brought new MAJORs. | Rules: review |
| L10 | Log as you go, with timestamps; never summarize after the fact. | v1: the entries for #3, #4, #6 and #15 were missing and needed a separate docs PR (#17). v2: several log stamps are marked "(approx.)". | Rules: logging; Done means (agent-prompts entries) |
| L11 | Never hand-edit generated decision files, and reproduce the export byte for byte before changing anything. | v1 S9 and K-05. v2 hand-edited `CLAUDE.md`, recorded in its `DEVIATIONS.md`. | OD-9; preflight board check |
| L12 | Keep scope honest against the budget. | v1's estimate grew from about 23 h to 45.7 h against a 16 h budget, and the hour-20 stop never happened. The most-refined rules, G11 (5 refinements) and G10 (4), grew from HTTP edge cases. | Rules: scope; frozen hardening; budget |
| L13 | A lesson written as text gets repeated; make it a guard. | v2 was given "use the routed path" (item 35 of its `lessons.md`), yet its new guard filter used `getRequestURI()`, and `/%69nventory/VER-1;lot=7` wrote stock (V-04). Duplicate matrices came back when tests were copied from main. | Rules: lessons become guards |
| L14 | Run the thing. Reviewers and the verifier run the stack. | v2: Playwright, not four code-reading reviewers, found the stale count caused by Spring's automatic 304. Compose found that the healthcheck image had no curl. | Self-critique: reviewers may run |
| L15 | Ask the critique for deletions, not justifications. | v2: removing Redis (#76) and the AOP interceptor (#81) both came from the owner. The critique had "recorded" those same issues (V-16, V-17, F-intv-10 as a MINOR). | Critique area 9; fix preference |
| L16 | GitHub mechanics. | Reviews go as COMMENT on your own PR. Only one PENDING review is allowed (a 422 once cost an issue). Issues must be closed by hand when the base isn't the default branch. Never re-issue a command the permission system denied (T-01). `<base>/<slug>` branch names can't exist while `<base>` does (v2 A1). | Rules: GitHub |
| L17 | One word, one meaning. | "V2" meant both build v2 and the `/v2` API; "V2" was also a decision and a migration. | "Words used here" |

## Design

| # | Lesson | Evidence | Enforced by |
|---|---|---|---|
| L18 | Never return an unbounded list. Default to a page and a `Link: rel="next"`. **Guard.** | C-02: 8 concurrent unpaged `GET /inventory` calls over 1M SKUs returned 500 with OutOfMemoryError. | OD-5; invariant 7 |
| L19 | Build `Link`s and path checks from the routed path, never the raw URI. **Guard:** ArchUnit forbids `HttpServletRequest.getRequestURI()` in main code outside a named allow-list. | C-08; v2 V-04. | Rules: lessons become guards |
| L20 | Tomcat rejects `%2F`, bad escapes, `%00` and oversized headers before Spring runs. A text/plain contract needs an error valve and tests against a real server. | C-01, build v1's only MAJOR; decision C1. | Frozen hardening; the real-server tests stay |
| L21 | `@PathVariable` drops `;matrix` content, so a request for one SKU could write another. **Guard:** the existing guard tests. | C-04 (`POST /inventory/ABC-1;lot=7` wrote to `ABC-1`); v2 M-14 (the HEAD variant). | Frozen hardening |
| L22 | Scope the global error advice to your own paths, or it rewrites `/actuator` and springdoc errors too. | C-06. | Frozen hardening |
| L23 | A claim-first idempotency row needs nullable response columns with an all-or-none CHECK, a stored Content-Type, and a retention decision. | Y4 vs R2; C-16. | Kept from build v2 (schema tests) |
| L24 | A mechanism can outlive its reason; remove it when the reason goes. | The AOP interceptor existed to sit between the retry and each SERIALIZABLE attempt. Once SERIALIZABLE and the retry were gone, it had no job; the explicit call cut the idempotency code from 472 to 218 lines (A33, #81). | `DESIGN.md` "what changed and why"; critique area 9 |
| L25 | Don't add infrastructure without a measured need, and make no performance claim without a benchmark. | v2 added Redis, then removed it. No benchmark was ever run, and every guarantee already held in Postgres (A30). | Rules: scope |
| L26 | Enforce operator-visible rules in the database and the compose file. **Guard:** the existing schema tests. | C-10 (append-only ledger, now a trigger, A11); C-12 (Postgres on loopback). | Kept from build v2 |
| L27 | Share one Postgres container and one test annotation. **Guard:** ArchUnit requires every `@SpringBootTest` class to use `@IntegrationTest`, and no test class may declare its own container. | v1 started 6 containers and 7 contexts for 788 tests; one static container cut the suite by about a third (C-20 to C-22, C-30). | Rules: move tests, don't copy them |
| L28 | Don't repeat one validation matrix at every layer. | PIT showed the removed duplicate rows killed 0 unique mutants (C-23 to C-28). v2 #61 cut the tests from 670 to 612, and #82 trimmed the matrices from 104 cases to 35. | Rules: move tests, don't copy them |
| L29 | The front end keeps its idempotency key on 0, 408, 429 and 5xx responses. **Guard:** the existing front-end test. | v2 V-01, a BLOCKER: a proxy 502 led to a new key and a double charge. | Front end (FE9) |
| L30 | A test of a hand-copied query proves nothing; test the query the code runs. | C-15. | Critique area 4 |
| L31 | Keep API versions from leaking into each other. **Guard:** ArchUnit forbids the unversioned controller from depending on the idempotency package, and a test shows an unversioned POST with the header writes nothing. | New for the final build. It is the core risk of OD-2 to OD-4. | Invariant 5 |

## Retired

| Lesson | Why it no longer applies |
|---|---|
| A SERIALIZABLE `SUM` predicate makes writers to unrelated SKUs conflict (C-03). | The balance row with a conditional UPDATE at READ COMMITTED (E1, build v2 §7) removed SERIALIZABLE and the retries. Build v2's `CrossSkuConcurrencyTest` stays as the regression test. |
| Redis failure modes: down, slow, flushed, restarted. | Redis was removed (A30–A32) and is out of scope. |
