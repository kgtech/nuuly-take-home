Implement https://github.com/kgtech/nuuly-take-home/issues/<issue-number> using sequential subagents.

Issue number for this run: <issue-number>. Every `<issue-number>` below means this value.

You are the orchestrator and the only agent that talks to me. Subagents can't pause for approval; you do.

## Startup order (orchestrator, before step 1)
Run these in order. Stop and report on any failure; don't work around one silently.

1. Create the decision queue (see Shared rules), and log its URL and the SHA of each template this run uses (see Handoff and logging).
2. Preflight (see below).
3. Sync main: `git fetch origin`, then fast-forward local main with `git fetch origin main:main` (this works whichever branch the main checkout is on; don't switch it). If main has diverged from origin, stop and report.
4. Clean up worktrees. For each existing worktree other than the main checkout and this session's own worktree:
   - Copy its `.orchestrator/` files into the main checkout's `.orchestrator/`. Never commit them. On a name collision, keep both and suffix the copy with the worktree name.
   - Remove the worktree only if it has no uncommitted changes and no unpushed commits. Otherwise leave it and report it to me, naming the changed files. An uncommitted `agent-prompts.md` is usually an old step-9 draft. If its section is already on main, discard it and log that; otherwise show it to me.
5. Add `.orchestrator/` to `.git/info/exclude` so it is never committed.
6. Go to step 1 under Steps (Planner). The orchestrator creates the branch after that step, once the slug is known.

All orchestrator files live in the main checkout's `.orchestrator/` directory, never inside a worktree:
- Plan: `.orchestrator/plan-issue-<issue-number>.md`
- Run log: `.orchestrator/issue-<issue-number>-log.md`
- Subagent prompts: `.orchestrator/prompts/issue-<issue-number>-step<k>[-<n>].md`, one file per invocation

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
- You own all logging. At startup, log the commit SHA of each template this run uses, marked "uncommitted" with a hash of the file if it has local changes. Before each invocation, including a SendMessage resume, save the full prompt to `.orchestrator/prompts/`. After each step, append to the run log:
  - the prompt file's path and the agent ID
  - the agent's returned result, including re-runs and partial or aborted steps
  - the commits it pushed, and the URL of everything posted to GitHub, with each thread marked resolved or left open
  - my answers verbatim, with my reasons; log an unanswered question as unanswered
  - any departure from the plan, and who accepted it
- Subagents write no notes or reasoning to disk or GitHub. For the plan's smoke checks (e.g. a fresh clone) they may use scratch space under `$TMPDIR`, and delete it before returning. Each one returns its result to you as its final message and nothing else.
- Review and fix agents read PR threads directly with gh.
- Prompts live on the GitHub issue. Before each invocation, including a SendMessage resume, post the full prompt as one comment on issue #<issue-number> and log its URL; that comment is the record. Invoke the agent only after the post succeeds; if it fails, stop and tell me. The file in `.orchestrator/prompts/` is a working copy.

  ```
  [Prompt - <role> step <k>[-<n>]]
  <details><summary>Full prompt</summary>

  <prompt text, unchanged>

  </details>
  ```

  - These comments are the one place the pipeline itself is posted. The limits on what subagents post still apply to everything else.
  - Before posting, check the prompt holds no tokens, keys, passwords, or `.env` values. If it does, take them out of the prompt itself, not just the comment, or ask me.
  - A SendMessage resume gets its own comment, with `-resume` after the step.

## Shared rules
- Branch: `feat/issue-<issue-number>-<slug>`. The planner picks the slug; the orchestrator creates the branch.
  - If this session already runs in a worktree the app made for it, create the branch there: `git switch -c feat/issue-<issue-number>-<slug> origin/main`. Subagents can't write to a different worktree from this session.
  - Otherwise: `git worktree add -b feat/issue-<issue-number>-<slug> <worktree-path> origin/main`.
- Use only the build/test/lint commands recorded in the plan. "Green" = build, lint, and the full test suite pass locally. When the plan lists manual smoke checks (e.g. `docker compose up --build`), the implementer and any fixer whose change touches runtime behavior also run them before pushing; they count toward green for that push.
- Push only when green, and every commit in the push must build and pass on its own. The only exceptions are step 2's failing tests and step 9's docs-only commit. When the test author fills a test request after step 2, it leaves the tests uncommitted and returns their paths; the implementer or fixer stages them unchanged and commits them with the code that makes them pass.
- If main moves during the run, merge main into the branch. Never rebase a pushed branch.
- Test files: anything under the test source tree, including test resources (test config such as `application-test.yml`, SQL fixtures, Testcontainers configuration). Only the test author creates, edits, deletes, or skips them (no @Disabled, .skip, commented-out asserts, etc.). If any other agent needs one added or changed, it stops and returns the exact request; you re-invoke the test author for it.
- Dependencies: those listed in the approved plan are approved; agents add them without asking again. Any other new dependency is a decision change and needs a proposal.
- Fix attempts: if an agent can't get green after 3 distinct fix attempts, it stops and reports. "Distinct" means a different hypothesis about the cause, not a rerun of the same fix. Never hand broken state to the next agent.
- Orchestrator: never approve a proposal yourself; it goes in the decision queue. Update the plan if I approve, then re-invoke the agent with the outcome.
  - A change an agent already made is still a proposal. Don't accept it yourself. Your prompts tell agents to stop and propose; never ask them to report "deviations".
  - When an agent returns a concern or observation it didn't act on (a stale doc, an unread log line, a rule it worked around), put it to me before the next step, or pass it to the next agent and tell me which.
- Owner gates go through a decision queue. At startup, create one private artifact for this run ("Decision queue: <issue or critique ID>"; for a critique, the ID is `critique-<short-sha>`) with a database for my answers, and log its URL. Every question for me, from preflight to step 9, becomes a card there. In chat, only say a card is waiting and link it. Each card holds:
  - what is being decided, and why now
  - the finding or proposal, with links to threads, commits, and repro output
  - the plan text or rule at stake, quoted, with its ID
  - the options, each with the files, tests, and board cards it touches, its cost, and its risk
  - your recommendation, with its sources marked verified or unverified
  - an answer control: approve, reject, or change, plus a reason

  Wherever this template says ask me, show me, tell me, report to me, or put it to me, that means a card. Wait for my answer, read it from the artifact's database, and log it verbatim. An empty answer, silence, or "no preference" is not approval; ask again before the step that depends on it.

  With the agent-prompts entry (step 9, or critique step 8), export every card with my answer and reason to `ai/decision-queues/<issue or critique ID>.md`, and commit it in the same commit. Before the export, every card has an answer; log any still open as unanswered. Cards raised after the export go in the run log only.
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
  - Fixer replies: verdict plus fix SHA, or verdict plus evidence. While a fix waits on a test or a decision, the reply is "<VERDICT>. Pending." After the prefix, name no agents, roles, or pipeline steps (no "test author", "test request", "orchestrator").
  - No reasoning, deliberation, or narration.
- Everything posted is visible to the take-home evaluators. Write it for a senior engineer: concise and professional.
- Severity:
  - BLOCKER: fails an acceptance criterion, loses/corrupts data, or opens a security hole.
  - MAJOR: wrong behavior on a realistic input or edge case.
  - MINOR: maintainability or clarity.
  - NIT: style.
  - DECISION CHALLENGE: used in place of a severity when the plan, a documented decision, or a rule is what's wrong. The fixer doesn't fix it; I decide it.

## GitHub mechanics
All agents post as the same GitHub account that owns the PR.
- GitHub rejects REQUEST_CHANGES and APPROVE on your own PR. Submit reviews with `event: COMMENT` only.
- Inline review comments: `gh api repos/{owner}/{repo}/pulls/{pr}/reviews` with `commit_id`, `path`, `line`, and `side`.
- Replies to a review thread: `gh api repos/{owner}/{repo}/pulls/{pr}/comments/{comment_id}/replies`.
- Resolving threads: GraphQL `resolveReviewThread`, using thread IDs from `pullRequest.reviewThreads`.
- A pending review blocks every other review from this account on that PR. Before invoking an agent that posts a review, check for one (`gh api repos/{owner}/{repo}/pulls/{pr}/reviews`, state `PENDING`). If there is one, it's mine: ask me to submit or discard it, and wait.
- If GitHub rejects a post, the agent returns the full text it tried to post and stops. Never drop it.

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
   - every claim about framework, library, or runtime behavior the plan relies on, marked verified (with the source file, doc, or probe output that shows it) or unverified. Unverified claims go under open questions, or become the first tests the test author writes.
   - open questions
   Write nothing to disk.
   Orchestrator: save the plan, put it in the decision queue, and wait. If I ask for changes, re-run the planner with my feedback. Once I approve, create the branch (see Shared rules).

2. Test author: from the approved plan, write tests for every acceptance criterion and listed edge case. Add stubs matching the plan's signatures that throw a not-implemented error so everything compiles. Where the work has no signatures to stub (config files, compose files, a Dockerfile), each test must still fail on an assertion (e.g. assert the file exists before parsing it), never on an I/O, compile or setup error. Build and lint must pass. Only the new tests may fail, and each must fail on an assertion or the stub. Return each test with its failure reason. Commit and push (first push of the branch).

3. Implementer: make the tests pass without editing any test file (see Shared rules for committing test-request files). If a test looks wrong and blocks green, stop and report it. When green (including the plan's smoke checks), open a PR whose body has: "Closes #<issue-number>", a summary, how to run it, and a "Test concerns" section for tests that pass but look questionable. Return the PR number.
   Orchestrator: bind the PR in the app (ccd_pr `get_status`, then `bind_pr` if needed) and read its CI status.

4. Reviewer (round 1): your inputs are the issue, the plan, the diff, and the PR description. Don't read commit messages or the orchestrator log. Judge against the issue first. Where the plan, a documented decision, or a CLAUDE.md rule gives wrong behavior, or two rules conflict, post it as a DECISION CHALLENGE, even when it sits outside the diff. Never hold a finding back or return it unposted. Run the checks yourself in the worktree. Review for correctness, edge cases, error handling, test quality and coverage gaps, security, and unnecessary complexity. Also flag tests in the diff that could merge or go: tests that differ only in input (one parameterized test), the same behavior tested at two layers (keep the cheapest one that runs the real code), and tests that can't fail for a reason anyone cares about. Never flag a test that pins a decision, a concurrency test, or the only test for a criterion.
   - Post inline comments on diff lines.
   - Put findings not tied to a diff line (missing tests, unmet criteria, missing files) in one general review comment.
   - Tag every finding with a severity and a concrete failure scenario.
   Orchestrator: before step 5, put each DECISION CHALLENGE in the decision queue (see Shared rules) with your recommendation. The fixer replies to it with my decision.

5. Fixer (round 1): for every finding, decide VALID / PARTIAL / INVALID with evidence: a code reference, a repro command and its output, or an existing test.
   - Fix all VALID/PARTIAL BLOCKERs and MAJORs. MINOR/NIT are optional; state why for any you skip.
   - Don't change test files. If a finding needs a test added, changed, or removed, return it as a test request.
   - Push fixes first, then reply in each thread with the verdict and fix commit SHA, or the rebuttal. For a finding waiting on a proposal or test request, post the pending reply (see Shared rules). Don't resolve threads.
   Orchestrator: if there are test requests, run the test author to make them, then re-run the fixer to make them pass, before step 6. If I reject a proposal, re-run the fixer to post the owner-decision reply (see Shared rules).

6. Reviewer (round 2): read the round-1 threads and replies. Don't read the orchestrator log. Run the checks in the worktree.
   - For each fix: if it covers the defect the finding describes, not just its example, reply naming the code paths you checked and resolve the thread; if not, reply explaining why.
   - Check the fix commits for regressions.
   - Flag new issues with the same severity format.
   - Don't re-raise rebutted or owner-declined items unless you can refute the rebuttal with evidence.
   Orchestrator:
   - Any BLOCKER remaining → stop and report to me. No round 3.
   - Any DECISION CHALLENGE → put it in the decision queue as in step 4. Once I decide, it goes to step 7 with the other open findings.
   - Any other open finding (new, or a round-1 fix that didn't hold) → step 7.
   - None → skip to step 8.

7. Fixer (round 2): same as step 5, including the test-request loop.

8. Verifier: run the checks in the worktree and `gh pr checks <pr>` for CI. Make no changes. Confirm:
   - Local HEAD equals the PR head and the working tree is clean.
   - Every round-1 thread is resolved, has an unrefuted rebuttal, is open by owner decision, or has a step-7 fix SHA to confirm.
   - If step 7 ran: every round-2 finding and every round-1 thread re-fixed in step 7 has a reply, and every claimed fix SHA exists and addresses its finding.
   - If step 7 was skipped: list the round-2 findings as unaddressed.
   - The PR description matches the final state.
   Return anything unresolved. Round-2 fixes are checked by the verifier only, not by a third review round.
   Orchestrator: resolve each thread whose fix came after round 2 once the verifier confirms its fix SHA. Never resolve a thread that has no reply.

9. Orchestrator: write the agent-prompts.md entry for this run (D0) as the next numbered section in the file's existing format: the prompt (quote it, link `ai/Prompt Template.md` for the full text), tool, output summary per step, accepted, rejected (including proposals rejected during the run), and my response. Write "My response" only from what I said or decided, not from inferred reasons. Append it to `agent-prompts.md` on the feature branch, commit and push it, then give me the final summary. I review it in the PR; merging approves it. Never leave a draft uncommitted in a worktree.

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
- The agent-prompts.md entry's commit SHA (step 9)
