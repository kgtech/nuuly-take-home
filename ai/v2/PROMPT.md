Build the entire project, as V2 of https://github.com/kgtech/nuuly-take-home, in this one run, from start to finish without human help:
- the inventory and purchase service described in `spec/`, covering every issue in `issues.md`
- your own storage design using Postgres and Redis, covering stock changes, concurrency, idempotency, and fast reads of the most-used stock counts
- a React and TypeScript front end

This prompt is the whole job. There is no later prompt for the next issue or the next part. Don't stop after one issue or one PR; keep going until everything under "Done means" is true or the budget runs out.

Nobody is available to answer questions or approve anything during this run. Never stop to wait for input. When you'd normally ask, make the most reasonable decision, record it (see Logging), and continue.

## Workspace
This repository already holds another build of the service on `main`. V2 is a new build on its own branch. You may read anything in the repository, including `main`, its history, and its issues and PRs, and reuse what helps. When you copy or adapt code from `main`, log it with the source path.

Setup, before anything else. You may start in any checkout of this repository, including another worktree. The main checkout is the first entry in `git worktree list`; call its path `<main>`.
1. Check that `<main>/.orchestrator/retro/fable-package/` exists and that this prompt has no unfilled `{{...}}` placeholders. If either check fails, write the reason to `<main>/.orchestrator/fable-setup-failed.md` and stop. Don't create any GitHub or git state first.
2. `git fetch origin`. If a `v2` branch already exists locally or on the remote, don't touch it. Write the reason to `<main>/.orchestrator/fable-setup-failed.md` and stop.
3. Create the V2 worktree on a new orphan branch, under the main checkout's worktree folder, so it starts with no files and no history from `main`: `git worktree add --orphan -b v2 <main>/.claude/worktrees/nuuly-take-home-v2`. Put the worktrees for V2 feature branches in the same folder.
4. Copy the contents of `<main>/.orchestrator/retro/fable-package/` into the root of the worktree.
5. In the worktree, commit the package as the first commit on `v2` and push `v2` to origin.

Reading is unrestricted. Writing is limited to V2:
- Make changes only inside `<main>/.claude/worktrees/nuuly-take-home-v2` and the worktrees you create for V2 feature branches.
- On GitHub, create and change only issues labeled `v2` and PRs whose base is `v2`. Don't comment on or change any other issue, PR, or branch.
- Never push to `main` or open a PR against it.

## GitHub for V2
- Issues: create one per item in `issues.md`, plus the storage-design and front-end issues you write. Title prefix `[v2]`, label `v2` (and `frontend` where it applies). Create the labels if they don't exist.
- Branches: `v2/<slug>`, created from `v2`.
- PRs: base `v2`. Title prefix `[v2]`. The body lists every issue the PR covers, as "Closes #<number>" lines.
- Closing issues: GitHub closes issues from "Closes #<number>" only when a PR merges into the default branch, and `v2` isn't the default. After each merge, close every issue the PR covers yourself with `gh issue close <number> --comment "Closed by #<pr>"`.
- CI: your workflow files live on `v2` and must trigger on pushes to `v2` and PRs targeting `v2`.

## What you have
- `spec/`: the original spec and assessment brief. The spec's contract wins over anything else here.
- `DECISIONS.md`: design decisions already made. Follow them, except those your V2 design supersedes (see "V2 storage design").
- `CLAUDE.md`: coding rules that follow from those decisions. Follow them, with the same exception.
- `openapi.yaml`: the API contract, including the documented additions to the spec.
- `issues.md`: the scope, as issues with acceptance criteria. "Requirements found in review" are part of the scope.
- `lessons.md`: what an earlier build of this service learned. Treat it as instructions, except where your V2 design supersedes it.

Lessons, summarized:
- Verify every framework and runtime claim before planning on it; write the proving test first when unsure (unchecked claims were disproved in 6 of 9 runs; two of two unsourced design claims were wrong).
- Tomcat rejects `%2F`, bad percent-escapes, `%00` and oversized headers before Spring runs; a text/plain error contract needs a Tomcat error valve, an `encodedSolidusHandling` decision and real-server tests, because MockMvc never sees these requests (C-01, the build's only MAJOR defect; decision C1, PR #31).
- Scope error handling deliberately: a global `@RestControllerAdvice` rewrites `/actuator/**` and springdoc errors too, and `@PathVariable` strips `;matrix` content so a request for one ID can change another SKU (C-06, C-04).
- Never return an unbounded list: 8 concurrent unpaged `GET /inventory` over 1,000,000 SKUs returned 500 with OutOfMemoryError; default to a page and a `Link: rel="next"` built from the routed path (C-02, C-08).
- Claim-first idempotency rows need nullable response columns with an all-or-none CHECK, a stored content type, and a retention decision (Y4 vs R2; C-16); under SERIALIZABLE a `SUM` predicate makes unrelated-SKU writers conflict, so test cross-SKU concurrency (C-03).
- Enforce operator-visible rules in the database and the compose file, not by convention: append-only ledger, Postgres bound to loopback, no committed password on 0.0.0.0 (C-10, C-12).
- Share one Postgres Testcontainer across all Spring contexts and use one meta-annotation for Boot tests; the first build started 6 containers and 7 contexts for 788 tests, and one static container cut the suite by about a third (C-20 to C-22, C-30). Don't repeat one validation matrix at every layer (C-23 to C-28).
- Add CI before the first PR (0 of 13 merged PRs had checks), push only commits that build and pass on their own (4 red commits in 3 runs), and stop and propose when a test or plan is wrong instead of working around it (4 wrong plans, each cleared in one cycle).
- Gate on the plan before code and decide before, not after, the verifier: plan-gate overrides cost 0 rework commits, post-verifier changes cost 1 to 8 commits each, and decide-first-check-after happened in 4 of 9 runs.
- GitHub: post reviews as COMMENT (you cannot approve your own PR), check for a PENDING review before posting (a 422 cost one issue and one escaped finding), close issues by hand when the PR base is not the default branch, and never re-issue a command the permission system denied.

Precedence when sources disagree: the spec and `openapi.yaml`, then the invariants in "V2 storage design", then your `DESIGN-V2.md` for the areas it covers, then `DECISIONS.md` and `CLAUDE.md`, then `lessons.md`.

## V2 storage design
The build on `main` uses Postgres only: conditional updates for stock, a ledger, and a Postgres idempotency table (G14, S8, Y4). V2 is your own approach, using both Postgres and Redis. Understand the current implementation first, then design something you can defend on its own terms. Don't port `main`, and don't bolt Redis onto it without a reason.

**1. Study the current implementation.** Before planning, read how `main` handles stock changes, concurrency, idempotency, the ledger, reads, and pagination, and the tests that cover them. Write `.fable/current-implementation.md`: how each works, what it guarantees, and where it is weak or limited, with file references.

**2. Design your approach.** Write `DESIGN-V2.md` at the root of `v2` and commit it before `.fable/plan.md`. It covers:
- What lives in Postgres and what lives in Redis, and which one is the source of truth for stock, for idempotency, and for reads.
- The purchase and add-stock flows, step by step, including what happens on a repeated `Idempotency-Key`.
- The read path for the most-used SKUs. Their count lookups must not wait on writes to the same SKU. State how stale a read can be, and how the design keeps it within that bound.
- How the design decides which SKUs are "most used", and what happens to the rest.
- Failure modes, and the behavior for each: Redis down, slow, flushed, or restarted; Postgres down; the service crashing between any two writes.
- At least two alternatives you considered, and why you rejected them.
- A comparison with the approach on `main`: what V2 does better, what it does worse, and what it costs in complexity.
- The `DECISIONS.md`, `CLAUDE.md`, and `lessons.md` entries your design supersedes, listed by ID, each with one line on why. Everything not on this list still applies.

You're free to choose the storage, concurrency, idempotency, and caching approach, the schema, and the internal structure. You're not free to change the API: the spec and `openapi.yaml` stay as they are, including the `Idempotency-Key` header, status codes, and text/plain errors.

**3. Invariants.** Whatever you design, these must hold, and each needs tests that would fail if it broke:
- **No oversell:** stock never goes below zero. When concurrent purchases ask for more than is in stock, exactly as many succeed as the stock allows.
- **Exactly once:** a request repeated with the same `Idempotency-Key` changes stock at most once and gets the same response, including when the repeats are concurrent and after Redis expires, evicts, or loses the key.
- **Durable:** once the service returns success for a purchase or stock addition, the change survives a restart of Redis and of the service. If Redis holds authoritative state, say how that's guaranteed.
- **Recorded:** Postgres holds a durable record of every stock change.
- **Bounded staleness:** a read never returns a quantity older than the bound stated in `DESIGN-V2.md`.
- **Defined failure:** with Redis unavailable, each endpoint either keeps working or fails with a defined, documented error. None silently breaks the other invariants.

**Tests** use real Postgres and Redis through Testcontainers, each shared across test classes. Include concurrency tests for each invariant, and fault tests: Redis stopped, flushed, and restarted during a run, and a crash between writes where your design has more than one.

**Performance claims.** A plain Postgres read doesn't wait on a row lock held by an update; it sees the last committed value. If `DESIGN-V2.md` claims V2 reads or writes are faster or less blocking than `main`, back it with a small benchmark that runs the same load against both, and record the numbers. Otherwise don't make the claim.

## Done means
- Every issue in `issues.md`, and the Redis and front-end issues you create, exists as a `v2` issue and is closed after the PR that covers it merges into `v2`.
- `v2` builds, passes lint and the full test suite locally for the service and the front end, and CI on `v2` is green.
- `main` and every non-V2 branch, issue, and PR are exactly as they were before the run.
- `.fable/current-implementation.md` and `DESIGN-V2.md` exist, and the code matches `DESIGN-V2.md`.
- The tests for every invariant pass.
- The README explains how to run the service with Postgres and Redis, the front end, and all tests, and states the assumptions from the spec gaps and the failure behavior.
- The self-critique has run, every BLOCKER and MAJOR it found is fixed or explained in the final report, and `.fable/critique.md` and `.fable/interview-defense.md` are on `v2`.
- `FABLE_REPORT.md` exists at the repo root (see Final report).

## Rules
- Decisions: follow the sources in the precedence order above. Superseding a decision through `DESIGN-V2.md` is not a deviation. Any other departure is, and only if the decision is unworkable: record it in `DEVIATIONS.md` with the decision ID, what you did instead, and the evidence that forced it. If you change the design during the run, update `DESIGN-V2.md` and log why. Never edit `DECISIONS.md` or `CLAUDE.md`. New decisions go in `DECISIONS-ADDED.md`, one line of reasoning each.
- Tests first: for every acceptance criterion, write the test before the production code that satisfies it, and confirm it fails for the right reason.
- Green: build, lint, and the full test suite pass for the service (including the invariant and fault tests) and the front end (type check, lint, unit tests, and production build). Push only when green, except the first push of a branch that holds only failing tests and stubs.
- Work plan: you decide how to group the issues into branches and PRs: one PR per issue, one for everything, or anything in between. Before writing code, write the plan to `.fable/plan.md`: each planned PR, the issues it covers, its order, and one line on why you grouped them that way. Commit it as the second commit on `v2`. If you change the plan during the run, update the file and log why.
- Review: before merging, review each PR against the issues it covers for correctness, edge cases, error handling, test quality, and security. For storage PRs, also review the invariants, consistency between Redis and Postgres, and behavior when Redis fails, against `DESIGN-V2.md`. Fix any BLOCKER or MAJOR first. Record the review as a PR comment with findings and fixes.
- Merge: you may merge your own PR into `v2` once it is green, CI passes, and the review has no open BLOCKER or MAJOR. Squash merge, then close the issues it covers. Never force-push. Never rewrite `v2` history.
- Stuck: after 3 distinct attempts at the same problem (each a different hypothesis about the cause), record it in the log, leave the work in a clearly marked state, and move on to other work. Come back to it once the rest is done.
- Budget: stop when you're done or when 4 hours of wall-clock time, counted from the start of Setup, is reached, whichever comes first. Keep at least a quarter of it for the self-critique and fix pass. If you hit the budget, stop cleanly: push what is green, and write the final report.
- GitHub text: commit messages, PR bodies, and comments are concise and professional. No reasoning or narration.
- How you organize the work (subagents, ordering, parallelism) is up to you, within these rules.

## Front end
Build it alongside the service, not after it. Front-end work on a feature can start as soon as that feature's contract is in `openapi.yaml`, using generated types and MSW mocks. The end-to-end test for a flow waits until the service endpoints it uses are merged.

Scope: a single-page app, in `frontend/`, for someone managing and selling stock:
- List all SKUs with their quantities. Support the optional paging parameters exactly as `openapi.yaml` and G9 define them, following the `Link` header for the next page.
- View one SKU.
- Add stock to a SKU, creating it if it doesn't exist, as the API allows.
- Purchase a quantity of a SKU.
- Send an `Idempotency-Key` on add stock and purchase, generated once per user action and reused on retry, so a double click or a network retry can't apply twice.
- Show the API's text/plain error message to the user as returned, for every failed request. Client-side validation may mirror the server's rules for fast feedback, but the server's response is what counts.
- Loading, empty, and error states for every view. Keyboard accessible, with labeled form controls. Usable at phone width.

Stack and rules:
- React with TypeScript in strict mode, built with Vite.
- Generate the API types from `openapi.yaml` (for example with openapi-typescript) and use them for every request and response, so a contract change fails the type check. Don't hand-write API types.
- Tests: Vitest and React Testing Library for components, with MSW mocking the API from the contract. Playwright end-to-end tests against the real service running locally: add stock then purchase, and a double-submitted purchase that changes stock once.
- In development, reach the API through the Vite dev server proxy. Don't change the service's API or behavior for the front end. If a service change turns out to be unavoidable (for example CORS for a production setup), record it in `DEVIATIONS.md`.
- Create GitHub issues for the front end yourself, with acceptance criteria, labeled `frontend`. They follow the same rules as the service issues and go in the same work plan.
- Front-end design choices (state management, routing, styling approach, and so on) go in `frontend/DECISIONS.md`, one line of reasoning each.

## Self-critique and fix
When every issue is merged and `v2` is green, critique the finished build and fix what the critique finds. This is part of the job, not an extra. Start it no later than when three quarters of the budget is used, even if some issues are unfinished, so it always runs.

**Independence.** The reviewers must not be the agents that wrote the code. Start each reviewer as a fresh subagent. Give it the package (`spec/`, `issues.md`, `DECISIONS.md`, `openapi.yaml`, this prompt's "V2 storage design" and "Front end" sections), `DESIGN-V2.md`, `DECISIONS-ADDED.md`, `frontend/DECISIONS.md`, and the code at the current `v2` SHA. Don't give it `.fable/log.md`, PR threads, or your own reasoning. Reviewers only read; they change nothing.

**Reviewers**, one per area, run in parallel:
- **Spec conformance:** every acceptance criterion and spec requirement is met, including status codes, response shapes, and text/plain errors.
- **Concurrency and data integrity:** conditional updates, the ledger, overflow and cap handling, anything that can lose or corrupt stock under concurrent requests.
- **Storage design:** every invariant holds, including under concurrency and with Redis stopped, slow, flushed, or restarted; the code does what `DESIGN-V2.md` says; the failure behavior matches what it documents; any performance claim has a benchmark behind it.
- **API contract and error handling:** code matches `openapi.yaml`, validation behaves as decided, framework errors don't leak JSON or stack traces.
- **Tests:** tests exercise the real SQL and Redis, cover the edge cases, and can't pass for the wrong reason. Flag tests that could be merged or that can't fail, and test classes that start their own container or Spring context without needing to.
- **Front end:** contract fidelity through the generated types, required views and states, idempotency keys reused on retry, accessibility, and phone width.
- **Security and operations:** input limits, injection, config and secrets, logging, and what an operator needs to run it.
- **Interview defense:** read the build as a senior interviewer reviewing a take-home. For every non-obvious choice (the V2 storage design and why it beats the approach on `main`, what Redis is trusted with, the idempotency flow, how hot SKUs are chosen, pagination, strict validation, the front-end stack), write the question the interviewer would ask and check whether the code, `DECISIONS.md`, `DECISIONS-ADDED.md`, and the README answer it: the reason, the alternative rejected, and the tradeoff accepted. A finding is a choice with no defensible answer on record, an answer the code contradicts, or complexity the take-home's scope doesn't justify.
- **Industry standards:** compare the design with how production commerce and payment APIs handle the same problems, and cite a source for every claim. Cover at least:
  - Shopify: how its Admin API models inventory (inventory items, levels, and locations), how it adjusts versus sets quantities, how it prevents overselling, and how it handles retries and idempotency for mutations.
  - Stripe's `Idempotency-Key` behavior, and the IETF HTTP API working group's Idempotency-Key header draft: key scope, lifetime, the response to a reused key with a different request, and the response while the first request is still in progress.
  - At least one other inventory API (for example Square, BigCommerce, or commercetools) for stock adjustments and concurrency.
  - Pagination and error-format conventions for public HTTP APIs.
  For each difference, say whether it matters at this service's scope. A difference is a finding only if a user or operator would be affected, or if it contradicts a standard the service claims to follow. Use web search and the providers' current documentation. If web access isn't available, say so and mark each claim UNVERIFIED instead of relying on memory. Don't repeat claims from `lessons.md` or the Opus design log without checking them.

**Findings.** Each has an ID (`F-<nn>`), area, severity (BLOCKER: fails an acceptance criterion, loses or corrupts data, double-charges, or opens a security hole; MAJOR: wrong behavior on a realistic input or edge case, or a choice with no defensible answer; MINOR: maintainability or clarity; NIT: style), location, what's wrong, a concrete failure scenario or the interview question it can't answer, and evidence: a code reference, a repro command and its output, a test, or a source link.

**Verify.** One more fresh subagent merges the findings, removes duplicates, and tries to reproduce every BLOCKER and MAJOR against the running service (for storage findings, including with Redis stopped or flushed). Mark each CONFIRMED, PLAUSIBLE, or REJECTED with the reason. Save the result as `.fable/critique.md` and commit it to `v2`.

**Fix.**
- Fix every CONFIRMED or PLAUSIBLE BLOCKER and MAJOR, then MINORs as budget allows. Fixes follow the same rules as the rest of the build: tests first, green before push, PRs into `v2` titled `[v2] fix: ...`, reviewed before merge. Group fixes into PRs as you see fit and list the finding IDs in each PR body.
- Interview defense findings are fixed by changing the code (often by simplifying), or by recording the reason, rejected alternative, and tradeoff in `DESIGN-V2.md` or `DECISIONS-ADDED.md`. Pick the one a senior interviewer would find more convincing.
- Industry standards findings are fixed by aligning with the standard, or by recording in `DECISIONS-ADDED.md` why this service differs, with the source. Changing the spec's contract or a `DECISIONS.md` decision to match a standard is a deviation and goes in `DEVIATIONS.md`.
- Record the outcome of every finding in `.fable/critique.md`: fixed (with PR), recorded (with the decision line), won't fix (with the reason), or rejected.

**Re-check.** After the fixes merge, run the verifier once more on the fixed findings only: confirm each repro no longer reproduces and the suite is green. Don't run a second full critique. Anything still failing goes in the final report as open.

**Interview prep.** Write `.fable/interview-defense.md`: the questions from the interview defense review, each with a short answer grounded in the final code and decision records, plus the three weakest points of the build and how to answer if asked about them.

## Logging
Keep `.fable/log.md` on `v2`, committed with the rest of the build. Append an entry for each of these, each with a UTC timestamp:
- Start and end of each PR, and of each phase within it (tests, implementation, review, fix, merge), naming the issues it covers
- Each subagent you start: its role, a one-line summary of what you asked, and its result
- Each decision or assumption you made where you'd normally ask a person, with one line of reasoning
- Each stop, retry, CI failure, and deviation
- Each time you copy or adapt code from `main`: the source path, the V2 path, and whether it was copied as is or changed

Tag every entry `[study]`, `[service]`, `[storage]`, `[frontend]`, or `[critique]` so each part can be measured separately. Studying `main` and writing `.fable/current-implementation.md` is `[study]`. `DESIGN-V2.md` and the work on stock changes, concurrency, idempotency, and reads is `[storage]`, including the service code it touches. The self-critique, its fixes, and the re-check are `[critique]`.

Don't summarize after the fact; append as you go.

## Final report
Write `FABLE_REPORT.md` at the root of `v2` with:
- The work plan as executed: each PR, the issues it covered, and how it differed from `.fable/plan.md`
- What was built, issue by issue, with the PR that covered it, listed under service, storage, and front end
- Decisions added, pointing to `DECISIONS-ADDED.md` and `frontend/DECISIONS.md`
- Decisions superseded by `DESIGN-V2.md`, and deviations from `DECISIONS.md`
- How V2's design differs from `main`, the invariants, the failure behavior, and the tests that show each
- Benchmark results, if the design makes a performance claim
- Decisions and assumptions made without a person
- What you reused from `main`, and what you rebuilt differently and why
- Anything unfinished or stuck, and why
- Self-critique: findings by area and severity, what was fixed, recorded, or left open, and a link to `.fable/critique.md`
- Industry standards: where the build matches Shopify and the other references, where it differs on purpose, and which claims are UNVERIFIED
- Known risks: where you're least confident the code is correct
