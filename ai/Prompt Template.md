Implement https://github.com/kgtech/nuuly-take-home/issues/<issue-number> using sequential subagents.

Issue number for this run: <issue-number>. Every `<issue-number>` below means this value.

You are the orchestrator and the only agent that talks to me. Subagents can't pause for approval; you do.

## Startup order (orchestrator, before step 1)
Run these in order. Stop and report on any failure; don't work around one silently.

1. Preflight (see below).
2. Sync main: `git fetch origin`, then fast-forward local main with `git fetch origin main:main` (this works whichever branch the main checkout is on; don't switch it). If main has diverged from origin, stop and report.
3. Clean up worktrees. For each existing worktree other than the main checkout and this session's own worktree:
   - Copy its `.orchestrator/` files into the main checkout's `.orchestrator/`. Never commit them. On a name collision, keep both and suffix the copy with the worktree name.
   - Remove the worktree only if it has no uncommitted changes and no unpushed commits. Otherwise leave it and report it to me, naming the changed files. An uncommitted `agent-prompts.md` usually means a step-9 draft that was never approved; show it to me rather than discarding it.
4. Add `.orchestrator/` to `.git/info/exclude` so it is never committed.
5. Go to step 1. The orchestrator creates the branch after step 1, once the slug is known.

All orchestrator files live in the main checkout's `.orchestrator/` directory, never inside a worktree:
- Plan: `.orchestrator/plan-issue-<issue-number>.md`
- Run log: `.orchestrator/issue-<issue-number>-log.md`
- Subagent prompts: `.orchestrator/prompts/issue-<issue-number>-step<k>[-<n>].md`, one file per invocation
- Step-9 draft: `.orchestrator/agent-prompts-issue-<issue-number>.md` until I approve it

Write orchestrator files with shell commands if the file tools refuse paths outside the session's worktree.

## Preflight
Check each item and report every failure to me with a proposed fix.
- Base branch: the default branch exists on the remote and has at least one commit, so the PR has a base.
- Push access: `git push --dry-run` to the remote works with the configured protocol (SSH or HTTPS via `gh auth setup-git`), and `gh auth status` is logged in with repo scope.
- Test runtime: whatever the test suite needs is reachable. For Testcontainers, `docker info` succeeds and Testcontainers resolves the same socket (DOCKER_HOST, /var/run/docker.sock, ~/.testcontainers.properties).
- Toolchains: the JDK and build tool versions the repo requires are installed or resolvable.
- Host ports: any port the issue's smoke checks bind (e.g. 8080 for the app) is free. If one is taken, report what holds it and ask what agents may do about it (stop and restart it, or use a fallback port), before step 1.
- Board export runtime: Node and a Playwright Chromium are available to run `ai/export-board.mjs`.
- agent-prompts.md: its section numbers are consecutive and every earlier issue run has an entry. Report any gap.
- Working tree: note any uncommitted changes so agents stage by path and never include them.
- Ignore plugin MCP servers that ask for authorization (GitHub, Slack, Linear, etc.). They are demo mocks; don't report them. Use `gh` for GitHub.

## Handoff and logging
- Each subagent starts fresh. Pass it: the issue URL, the branch name, the absolute worktree path (from step 2 on), the PR number (from step 4 on), and the path to the plan.
- Subagents work only inside the worktree path. They never touch the main checkout.
- You own all logging. Before each invocation, save the full prompt to `.orchestrator/prompts/`. After each step, append to the run log: the step, the prompt file's path, the agent's returned result, the commits it pushed, and any decisions I made.
- Subagents write no notes, scratch files, or reasoning to disk or GitHub. Each one returns its result to you as its final message and nothing else.
- Review and fix agents read PR threads directly with gh.

## Shared rules
- Branch: `feat/issue-<issue-number>-<slug>`. The planner picks the slug; the orchestrator creates the branch.
  - If this session already runs in a worktree the app made for it, create the branch there: `git switch -c feat/issue-<issue-number>-<slug> origin/main`. Subagents can't write to a different worktree from this session.
  - Otherwise: `git worktree add -b feat/issue-<issue-number>-<slug> <worktree-path> origin/main`.
- Use only the build/test/lint commands recorded in the plan. "Green" = build, lint, and the full test suite pass locally. When the plan lists manual smoke checks (e.g. `docker compose up --build`), the implementer and any fixer whose change touches runtime behavior also run them before pushing; they count toward green for that push.
- Push only when green. The only exception is step 2.
- If main moves during the run, merge main into the branch. Never rebase a pushed branch.
- Test files: anything under the test source tree, including test resources (test config such as `application-test.yml`, SQL fixtures, Testcontainers configuration). Only the test author creates, edits, deletes, or skips them (no @Disabled, .skip, commented-out asserts, etc.). If any other agent needs one added or changed, it stops and returns the exact request; you re-invoke the test author for it.
- Dependencies: those listed in the approved plan are approved; agents add them without asking again. Any other new dependency is a decision change and needs a proposal.
- Fix attempts: if an agent can't get green after 3 distinct fix attempts, it stops and reports. "Distinct" means a different hypothesis about the cause, not a rerun of the same fix. Never hand broken state to the next agent.
- Orchestrator: never approve a proposal yourself. Show it to me with your recommendation and wait. An answer of "no preference" is not approval; ask again. Log my decision, update the plan if I approve, then re-invoke the agent with the outcome.
- Subagents don't change decisions on their own. A decision is anything in the approved plan (interfaces, layout, test cases, commands) or recorded in repo docs (README, ADRs, design notes). If an agent needs to change one, it stops before making the change and returns a proposal: what to change, why, and what it affects.
- When I approve a decision change, the orchestrator posts a comment on issue #<issue-number> before re-invoking the agent, with each field on its own line:

  ```
  [Decision change] <what changed>
  Was: <previous decision>
  Now: <new decision>
  Why: <one line>
  Affects: <files, interfaces, or tests>
  ```

  - Don't edit the issue body. The original acceptance criteria stay as written; changes live in the comments.
  - Rejected proposals are recorded in the run log only, not posted as issue comments. Step 9 must include them.
- When I reject a proposal tied to a review finding, re-invoke the fixer to reply in that thread, prefixed `[Round N - Fixer]`: "Owner decision: not changed in this PR." The thread stays unresolved. Reviewers and the verifier treat it like a rebutted finding: don't re-raise it, and report it as open by owner decision.
- Decisions recorded in DECISIONS.md or CLAUDE.md come from the decision board (S9). If I approve a change to one, the orchestrator:
  1. Dumps the board state from the board artifact's database (`https://claude.ai/artifact/Ma9JpFCmsLHQpJgWXbwpBT`, collection `decisions`, one JSON file per decision) to a scratch directory.
  2. Runs `ai/export-board.mjs` against the unchanged board and that dump, with the date from the committed DECISIONS.md header, and confirms the output matches the committed DECISIONS.md and CLAUDE.md byte for byte. If it doesn't, stop and report; change nothing. (The script needs the `playwright` package next to it: copy it into a scratch directory with `npm i playwright` there. If the package and the installed browsers are different versions, set `PW_EXE` to an installed Chromium.)
  3. Updates `ai/decision-board.html`.
  4. Regenerates DECISIONS.md and CLAUDE.md with the same script against the updated board and today's date.
  5. Commits the board and both exports together on the feature branch.
  6. Reads the published board, confirms it matches the committed board apart from the host's page wrapper, and republishes it to the same URL. If it doesn't match, or publishing isn't available, ask me to republish instead.
  Never hand-edit DECISIONS.md or CLAUDE.md.
- Keep repo documentation current. When code changes behavior, configuration, or run/test commands, update the affected docs in the same commit, but only to describe what was already decided. Changing a documented decision requires my approval first.
- Keep the PR description current. When a fixer's commits change behavior, docs, or how to run the project, it updates the PR body's summary and how-to-run sections.
- Never merge. Never force-push.
- Prefix every PR comment with `[Round N - <role>]`.
- Anything a subagent posts to GitHub is limited to what the step requires:
  - Commit messages: what changed, one line plus optional short body.
  - Review comments: severity, finding, failure scenario.
  - Fixer replies: verdict plus fix SHA, or verdict plus evidence.
  - No reasoning, deliberation, or narration.
- Everything posted is visible to the take-home evaluators. Write it for a senior engineer: concise and professional.
- Severity:
  - BLOCKER: fails an acceptance criterion, loses/corrupts data, or opens a security hole.
  - MAJOR: wrong behavior on a realistic input or edge case.
  - MINOR: maintainability or clarity.
  - NIT: style.

## GitHub mechanics
All agents post as the same GitHub account that owns the PR.
- GitHub rejects REQUEST_CHANGES and APPROVE on your own PR. Submit reviews with `event: COMMENT` only.
- Inline review comments: `gh api repos/{owner}/{repo}/pulls/{pr}/reviews` with `commit_id`, `path`, `line`, and `side`.
- Replies to a review thread: `gh api repos/{owner}/{repo}/pulls/{pr}/comments/{comment_id}/replies`.
- Resolving threads: GraphQL `resolveReviewThread`, using thread IDs from `pullRequest.reviewThreads`.

## Steps
1. Planner: read the issue and the repo. Pick the slug. Don't create a branch. Return the plan to the orchestrator with:
   - the slug
   - exact build, test, and lint commands, plus any manual smoke commands
   - acceptance criteria mapped to tasks
   - file/module layout
   - public interfaces (signatures, API contracts)
   - new dependencies, each with the reason
   - test cases per criterion, including edge cases
   - host resources the checks need (ports, containers)
   - open questions
   Write nothing to disk.
   Orchestrator: save the plan, show it to me, and wait. If I ask for changes, re-run the planner with my feedback. Once I approve, create the branch (see Shared rules).

2. Test author: from the approved plan, write tests for every acceptance criterion and listed edge case. Add stubs matching the plan's signatures that throw a not-implemented error so everything compiles. Where the work has no signatures to stub (config files, compose files, a Dockerfile), each test must still fail on an assertion (e.g. assert the file exists before parsing it), never on an I/O, compile or setup error. Build and lint must pass. Only the new tests may fail, and each must fail on an assertion or the stub. Return each test with its failure reason. Commit and push (first push of the branch).

3. Implementer: make the tests pass without touching any test file. If a test looks wrong and blocks green, stop and report it. When green (including the plan's smoke checks), open a PR whose body has: "Closes #<issue-number>", a summary, how to run it, and a "Test concerns" section for tests that pass but look questionable. Return the PR number.
   Orchestrator: bind the PR in the app (ccd_pr `get_status`, then `bind_pr` if needed) and read its CI status.

4. Reviewer (round 1): your inputs are the issue, the plan, the diff, and the PR description. Don't read commit messages or the orchestrator log. Judge against the issue first, and flag anywhere the plan itself is wrong. Run the checks yourself in the worktree. Review for correctness, edge cases, error handling, test quality and coverage gaps, security, and unnecessary complexity.
   - Post inline comments on diff lines.
   - Put findings not tied to a diff line (missing tests, unmet criteria, missing files) in one general review comment.
   - Tag every finding with a severity and a concrete failure scenario.

5. Fixer (round 1): for every finding, decide VALID / PARTIAL / INVALID with evidence: a code reference, a repro command and its output, or an existing test.
   - Fix all VALID/PARTIAL BLOCKERs and MAJORs. MINOR/NIT are optional; state why for any you skip.
   - Don't change test files. If a finding needs a new or changed test, return it as a test request.
   - Push fixes first, then reply in each thread with the verdict and fix commit SHA, or the rebuttal. For a finding waiting on a proposal or test request, reply with the verdict and say it's pending. Don't resolve threads.
   Orchestrator: if there are test requests, run the test author to add them, then re-run the fixer to make them pass, before step 6. If I reject a proposal, re-run the fixer to post the owner-decision reply (see Shared rules).

6. Reviewer (round 2): read the round-1 threads and replies. Don't read the orchestrator log. Run the checks in the worktree.
   - For each fix: if it resolves the finding, resolve the thread; if not, reply explaining why.
   - Check the fix commits for regressions.
   - Flag new issues with the same severity format.
   - Don't re-raise rebutted or owner-declined items unless you can refute the rebuttal with evidence.
   Orchestrator:
   - No BLOCKER/MAJOR remaining → skip to step 8.
   - Any BLOCKER remaining → stop and report to me. No round 3.
   - MAJORs only → step 7.

7. Fixer (round 2): same as step 5, including the test-request loop.

8. Verifier: run the checks in the worktree and `gh pr checks <pr>` for CI. Make no changes. Confirm:
   - Local HEAD equals the PR head and the working tree is clean.
   - Every round-1 thread is resolved, has an unrefuted rebuttal, or is open by owner decision.
   - If step 7 ran: every round-2 finding has a reply, and every claimed fix SHA exists and addresses its finding.
   - If step 7 was skipped: list the round-2 findings as unaddressed.
   - The PR description matches the final state.
   Return anything unresolved. Round-2 fixes are checked by the verifier only, not by a third review round.

9. Orchestrator: draft the agent-prompts.md entry for this run (D0) in `.orchestrator/agent-prompts-issue-<issue-number>.md`, as the next numbered section in the file's existing format: the prompt (quote it, link `ai/Prompt Template.md` for the full text), tool, output summary per step, accepted, rejected (including proposals rejected during the run), and my response. Write "My response" only from what I said or decided, not from inferred reasons. Show it to me. When I approve it, append it to `agent-prompts.md` on the feature branch, commit and push. Never leave a draft uncommitted in a worktree.

## Final summary
- PR link
- Per round: findings raised / fixed / rejected, by severity
- Test requests made and whether they were filled
- Unaddressed MINOR/NIT items, and any round-2 findings left unaddressed
- CI status
- Open questions from the plan
- Anything the verifier flagged, noting that round-2 fixes were verified, not re-reviewed
- Worktrees left in place during cleanup, and why
- Deviations from this template, and why
- Anything left for me to decide
- Path to the run log
- The drafted agent-prompts.md entry (step 9), awaiting my approval
