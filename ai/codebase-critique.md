Critique the codebase at https://github.com/kgtech/nuuly-take-home after all issues in the repo have been implemented. Post the findings as review comments on a draft critique PR so I can review notes and code side by side, then fix the findings I approve.

You are the orchestrator and the only agent that talks to me. Subagents can't pause for approval; you do.

Read `ai/Prompt Template.md` first. Its Preflight, Shared rules, GitHub mechanics, and Severity sections apply to this run unless overridden below. Pass the relevant rules to each subagent; they start fresh and don't read that file themselves.

## Startup order (orchestrator, before step 1)
Run these in order. Stop and report on any failure; don't work around one silently.

1. Create the decision queue and log the template SHAs, as in `ai/Prompt Template.md`.
2. Preflight, as in `ai/Prompt Template.md`.
3. Sync main, as in `ai/Prompt Template.md`.
4. Clean up worktrees, as in `ai/Prompt Template.md`.
5. Add `.orchestrator/` to `.git/info/exclude`.
6. Record the baseline: the main SHA every reviewer will review. Create a detached, read-only review worktree at that SHA: `git worktree add --detach <review-path> <sha>`.
7. Confirm open issues. If any issue is still open without a merged PR, list them and ask whether to continue.
8. Check the critique PR base (see below): find main's root commit with `git rev-list --max-parents=0 <sha>`. List the source, test, and config files that already exist in the root commit; those won't appear in the PR diff. Show me the list and wait before continuing.

Orchestrator files, all in the main checkout's `.orchestrator/`:
- Inventory: `.orchestrator/critique-inventory.md`
- Findings report: `.orchestrator/critique-report.md`
- Run log: `.orchestrator/critique-log.md`

## The critique PR
A draft PR whose diff is the whole codebase, so every finding can sit on the line it's about.
- Base branch: `critique/base-<short-sha>`, pointing at main's root commit.
- Head branch: `critique/review-<short-sha>`, pointing at the baseline SHA. No new commits.
- Open it as a draft: `gh pr create --draft`. Title: `Critique: <short-sha> (review only, not for merge)`. Body: the baseline SHA, what the PR is for, the severity definitions, a findings table (ID, severity, verdict, dimension, file), and a note that fixes arrive as separate PRs linked from each thread.
- GitHub needs the base and head to share history, which is why the base is the root commit rather than an empty orphan branch.
- Never merge it, never push commits to it, and never mark it ready for review. Close it only when I say so.
- Delete both critique branches only after I approve closing the PR.

## Handoff and logging
- Pass each subagent: the repo URL, the baseline SHA, the review worktree path, the inventory path (from step 2 on), and the critique PR number (from step 4 on).
- Log as in the Handoff and logging section of `ai/Prompt Template.md`, with every field it lists. Save prompts as `.orchestrator/prompts/critique-step<k>[-<n>].md`, and save the triage table I approve in the run log.
- Prompts go on the critique PR in the Prompt Template's `[Prompt - <role> step <k>[-<n>]]` format, the one exception to the no-narration rule below and to the Prompt Template's `[Round N - <role>]` prefix. Until step 4 opens the PR, the local file is the record; post steps 1 to 3 right after it opens. From step 4 on, the PR comment is the record, and the Prompt Template's post-before-invoke and secret-check rules apply. Step 6's issue pipelines post theirs on their own issues (Prompt Template, Handoff and logging).
- Subagents write nothing to disk or GitHub except where a step says so. Each returns its result as its final message and nothing else.

## Rules for this run
- Steps 1 to 3 are read-only. No commits, no pushes, no GitHub posts, no edits in the review worktree. Step-2 reviewers run no Gradle, Docker, or other containers anywhere; they cite code and tests and leave repros to the verifier. The verifier runs repros only in its disposable worktree and stops every container it started before returning.
- Every reviewer reviews the baseline SHA only. If main moves during the run, don't re-review; note it in the log.
- A finding needs a location (file and line range, or "missing"), a severity, a concrete failure scenario, and evidence: a code reference, a repro command and its output, or an existing test.
- Findings that contradict a documented decision (DECISIONS.md, CLAUDE.md, or a [Decision change] comment on an issue) are filed as decision challenges, not defects. They go to me as proposals, per the decision rules in `ai/Prompt Template.md`.
- Don't re-raise an item already rebutted in a PR thread unless you can refute the rebuttal with evidence. Unaddressed MINOR/NIT items from earlier PRs may be re-raised; mark them "carried over" with the PR link.
- Nothing is posted to GitHub until I approve the findings list in step 3.
- Everything posted is visible to the take-home evaluators. Write it for a senior engineer: concise and professional, no reasoning or narration.
- Every owner gate in this file goes through the decision queue (Prompt Template, Shared rules).

## Steps
1. Inventory (one agent): build the map the reviewers work from. Read:
   - Every issue in the repo, open and closed (`gh issue list --state all`), with its body and all comments, including [Decision change] comments.
   - Every merged PR, with its review threads, verdicts, and any "Test concerns" section.
   - DECISIONS.md, CLAUDE.md, README, `docs/spec/`, and `openapi.yaml`.
   Run the build, lint, and full test suite in the review worktree using the commands from the plans or README, and report the result.
   Return:
   - Each acceptance criterion, per issue, mapped to the code and tests that implement it. Mark any criterion with no test.
   - Each decision mapped to where the code enforces it.
   - Carried-over items: unaddressed MINOR/NIT findings and test concerns from earlier PRs.
   - Test inventory: every test method (count parameterized cases separately), with its class, layer (unit, slice such as `@WebMvcTest` or `@DataJpaTest`, or full integration with Testcontainers), runtime from the test reports, and the acceptance criterion or decision ID it traces to, or "none".
   - Test totals: count and total runtime per layer, and the 10 slowest test classes.
   - Baseline check result.
   Orchestrator: save it to the inventory file and show me a short summary, including the test totals. If the baseline isn't green, stop and report.

2. Reviewers (in parallel, one per dimension, read-only): each gets the inventory and reviews the whole codebase at the baseline SHA for its dimension only.
   - **Spec conformance:** every acceptance criterion and spec requirement is met, including status codes, response shapes, and the text/plain error contract.
   - **Concurrency and data integrity:** transactions, conditional updates, upserts, the stock ledger, overflow and cap handling, anything that can lose or corrupt data under concurrent requests.
   - **API contract and error handling:** code matches `openapi.yaml`, validation behaves as decided, framework errors don't leak JSON or stack traces, error messages match the decisions.
   - **Tests:** tests exercise the SQL that ships, cover edge cases in the plans, and can't pass for the wrong reason. Flag flaky patterns, order dependence, and assertions that check too little.
   - **Cross-cutting design:** problems that sit between PRs. Duplicated logic, inconsistent patterns, drift from CLAUDE.md rules, dead code, and docs that no longer describe the code.
   - **Security and operations:** input limits, injection, config and secrets, logging, startup and shutdown, what an operator needs to run it.
   - **Test suite economy:** whether the suite is larger or slower than the behavior it protects. The Tests reviewer covers missing and weak tests; this reviewer covers tests that could go or merge. Classify candidates as:
     - **CONSOLIDATE:** tests that differ only in input or expected value (merge into one `@ParameterizedTest`); the same behavior tested at more than one layer (keep it at the cheapest layer that still exercises the real code path, for example keep SQL behavior in the Testcontainers test and drop the mocked-repository copy); repeated setup that belongs in a shared fixture.
     - **NOISE:** tests that can't fail for a reason anyone cares about. Examples: asserting getters, setters, or generated OpenAPI model classes; verifying a mock returns what it was stubbed to return; assertions that pass whatever the code does; exact duplicates of another test; tests of Spring or Jackson behavior that no decision depends on.
     - **PROTECTED (never classify as noise):** tests that pin a decision even when they look like framework tests (for example Jackson coercion for G2, text/plain errors for G1); concurrency tests; the only test tracing to an acceptance criterion.
     For each candidate, give the test names, the proposed change (the merged test's shape, or "delete"), which acceptance criteria and decisions stay covered and by which test, and the test count and runtime saved.
     Severity: MINOR by default. MAJOR when a NOISE test is the only one tracing to an acceptance criterion, because that criterion is effectively untested; report it as a coverage gap as well.
   Return findings as a list, each with: dimension, severity, file and line range, finding, failure scenario, evidence, related issue or decision ID, and "carried over" or "decision challenge" if either applies. For test suite economy findings, use the failure scenario field for the cost instead: what the test adds in runtime or maintenance and what it fails to catch.

3. Verifier (one agent): merge all reviewer findings.
   - Dedupe. Keep the strongest evidence and list every dimension that raised it.
   - For each BLOCKER and MAJOR, try to reproduce it: run a repro command against the app, or write a throwaway test in a separate disposable worktree that you delete before returning. Never commit it.
   - Mark each finding CONFIRMED (reproduced), PLAUSIBLE (evidence but no repro), or REJECTED (with the reason).
   - Downgrade any severity the evidence doesn't support, and say why.
   - For CONSOLIDATE and NOISE findings, get evidence with mutation testing (PIT) in a disposable worktree. Add the PIT plugin config there only; never commit it. Scope it to the classes under test so it finishes in reasonable time, and record the command. Uniqueness is per PIT scope (for example, the no-DB run and the Testcontainers run): a test is confirmed redundant only if, in every scope it runs in, it kills no mutant that another kept test doesn't also kill. A test that is the only killer of a mutant in any scope is not noise; mark the finding REJECTED and name the mutant. If PIT can't run against a layer (for example, Testcontainers tests that exceed the time budget), mark those findings PLAUSIBLE and say why.
   - Record the baseline mutation score for the scoped classes; fixes must not lower it.
   - Assign each finding an ID: `C-<nn>`.
   Return the merged list, sorted by severity, then confidence.
   Orchestrator: save it to the report file. Show me a count by severity and verdict, the REJECTED findings with reasons, and the decision challenges. Wait for my approval to post. Only CONFIRMED and PLAUSIBLE findings are posted; REJECTED ones stay in the report.

4. Post (orchestrator): create the base and head branches, push them, and open the draft critique PR. Then post one review with `event: COMMENT`:
   - One inline comment per finding, on the RIGHT side of its line range (`start_line` and `line` for multi-line). Format:
     ```
     [Critique - <dimension>] C-<nn> <SEVERITY> (<CONFIRMED|PLAUSIBLE>)
     Finding: <one or two lines>
     Failure scenario: <concrete input or sequence and the wrong result>
     Evidence: <repro command and output, test name, or code reference>
     Related: <issue #, PR #, or decision ID>
     ```
   - Decision challenges use the same format with `DECISION CHALLENGE` in place of the severity.
   - Test suite economy findings add the type after the severity (`MINOR CONSOLIDATE` or `MINOR NOISE`), sit on the first listed test, and replace "Failure scenario" with:
     ```
     Tests: <test names>
     Proposed: <merged test shape, or delete>
     Coverage kept: <criteria and decisions, and the test that keeps each>
     Saves: <test count and runtime>
     ```
   - Findings that can't sit on a diff line ("missing" findings, files in the root commit) go in the review's general comment, each with its ID and the same fields.
   - The general comment also includes a test suite table: test count and runtime per layer now, and after all proposed CONSOLIDATE and NOISE changes, plus the baseline mutation score.
   Return the PR number and a map of finding ID to thread ID.

5. Triage (me): I review the PR and decide each finding in the decision queue (one card per finding, linking its thread), with one of the options below. A reply from me in a thread counts too; copy it into the card:
   - `fix`: goes to step 6.
   - `defer`: the thread stays open as a record; no issue this run.
   - `wontfix: <reason>`
   - `decision`: for a decision challenge I approve. It goes to step 6 like `fix`. Post its [Decision change] comment (format in `ai/Prompt Template.md` Shared rules) on the fix issue when you create it; the board change runs inside that issue's pipeline, which doesn't post the comment again.
   Orchestrator: read thread replies with gh. Ask me about any finding without a decision. Log every choice. Then reply in each thread with the outcome prefixed `[Triage]`, and resolve the thread for `wontfix` and rejected decision challenges only. Ask me before grouping findings into one fix.

6. Fix (orchestrator): for each `fix` or `decision` finding, in severity order:
   - Create a GitHub issue with the label `critique`. Title: `C-<nn>: <finding>`. Body: finding, failure scenario, evidence, a link to the critique thread, and acceptance criteria for the fix, including a test that fails before the fix and passes after.
   - Reply in the critique thread with the issue link.
   - Run the full issue pipeline in `ai/Prompt Template.md` with that issue number. Finish it, including its step 9 entry, before starting the next.
   Remove the review worktree before starting fixes.

   Test-only findings (CONSOLIDATE and NOISE) have no failing test to write first, so they run a shorter version of the pipeline:
   - Issue acceptance criteria instead of a failing test: the listed tests are merged or removed as proposed; every acceptance criterion and decision in "Coverage kept" still traces to a test; the mutation score for the scoped classes is not lower than the baseline; the suite is green.
   - Group them into one issue per test class, not one per finding, unless I say otherwise.
   - Pipeline steps: planner, test author (makes the change and opens the PR, with the before and after test count, runtime, and mutation score in the body), reviewer, fixer, verifier. Skip the implementer. The test author is still the only agent that touches tests; the other rules apply unchanged.
   - Any production code change found necessary during these fixes stops the run and comes to me as a proposal.

7. Final check (one agent): after I tell you the fix PRs are merged, sync main and run the full checks. For each fixed finding, confirm its repro no longer reproduces. Then, in the critique PR:
   - Fixed: reply `[Final check] Fixed in #<pr>` and resolve the thread.
   - Still reproduces: reply with the repro output and leave the thread open.
   Return anything that still reproduces.

8. Orchestrator: write the agent-prompts.md entry for this critique run (D0), in the file's existing format: the prompt (quote it, link `ai/codebase-critique.md`), tool, output summary per step, accepted, rejected, and my response. Write "My response" only from what I said or decided. Commit it on a new branch from main (`docs/critique-<short-sha>-agent-prompts`), push, and open a PR for it; I review it there. Never leave a draft uncommitted in a worktree. Then ask me whether to close the critique PR.

## Final summary
- Critique PR link and baseline SHA
- Findings by severity and verdict: raised, confirmed, rejected, fixed, deferred, won't fix
- Decision challenges and what I decided
- Carried-over items and what happened to each
- Test suite: count, runtime per layer, and mutation score at baseline and after fixes
- Issues and fix PRs, with links and status
- Anything still reproducing after step 7
- Files in the root commit that couldn't take inline comments
- Path to the report and run log
- The agent-prompts.md entry's PR link