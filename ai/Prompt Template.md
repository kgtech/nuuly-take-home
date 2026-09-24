Implement https://github.com/kgtech/nuuly-take-home/issues/<issue-number> using sequential subagents.

You are the orchestrator and the only agent that talks to me. Subagents can't pause for approval; you do.

## Handoff and logging
- Each subagent starts fresh. Pass it: the issue URL, the branch name, the PR number (from step 4 on), and the path to the plan (.orchestrator/plan-issue-1.md).
- You own all logging. Keep one log at .orchestrator/issue-1-log.md. Before step 1, add .orchestrator/ to .git/info/exclude so it is never committed. After each step, append: the step, the agent's returned result, the commits it pushed, and any decisions I made.
- Subagents write no notes, scratch files, or reasoning to disk or GitHub. Each one returns its result to you as its final message and nothing else.
- Review and fix agents read PR threads directly with gh.

## Shared rules
- Branch: feat/issue-1-<slug>. The planner picks the slug.
- Use only the build/test/lint commands recorded in the plan. "Green" = build, lint, and the full test suite pass.
- Push only when green. The only exception is step 2.
- Only the test author creates, edits, deletes, or skips tests (no @Disabled, .skip, commented-out asserts, etc.). If any other agent needs a test added or changed, it stops and returns the exact request; you re-invoke the test author for it.
- If an agent can't get green after ~3 distinct fix attempts, it stops and reports. Never hand broken state to the next agent.
- Never merge. Never force-push.
- Prefix every PR comment with [Round N – <role>].
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

## Steps
1. Planner: read the issue and the repo. Pick the slug and create the branch locally (don't push; it has no commits yet). Return the plan to the orchestrator with:
   - exact build, test, and lint commands
   - acceptance criteria mapped to tasks
   - file/module layout
   - public interfaces (signatures, API contracts)
   - test cases per criterion, including edge cases
   - open questions
   Write nothing to disk.
   Orchestrator: save the plan to .orchestrator/plan-issue-1.md, show it to me, and wait. If I ask for changes, re-run the planner with my feedback.

2. Test author: from the approved plan, write tests for every acceptance criterion and listed edge case. Add stubs matching the plan's signatures that throw a not-implemented error so everything compiles. Build and lint must pass. Only the new tests may fail, and each must fail on an assertion or the stub, not on compile or setup errors. Return each test with its failure reason. Commit and push (first push of the branch).

3. Implementer: make the tests pass without touching any test file. If a test looks wrong and blocks green, stop and report it. When green, open a PR whose body has: "Closes #1", a summary, how to run it, and a "Test concerns" section for tests that pass but look questionable. Return the PR number.

4. Reviewer (round 1): your inputs are the issue, the plan, the diff, and the PR description. Don't read commit messages or the orchestrator log. Judge against the issue first, and flag anywhere the plan itself is wrong. Check out the branch and run the checks yourself. Review for correctness, edge cases, error handling, test quality and coverage gaps, security, and unnecessary complexity.
   - Post inline comments on diff lines.
   - Put findings not tied to a diff line (missing tests, unmet criteria, missing files) in one general review comment.
   - Tag every finding with a severity and a concrete failure scenario.

5. Fixer (round 1): for every finding, decide VALID / PARTIAL / INVALID with evidence: a code reference, a repro command and its output, or an existing test.
   - Fix all VALID/PARTIAL BLOCKERs and MAJORs. MINOR/NIT are optional; state why for any you skip.
   - Don't change tests. If a finding needs a new or changed test, return it as a test request.
   - Push fixes first, then reply in each thread with the verdict and fix commit SHA, or the rebuttal. Don't resolve threads.
   Orchestrator: if there are test requests, run the test author to add them, then re-run the fixer to make them pass, before step 6.

6. Reviewer (round 2): read the round-1 threads and replies. Don't read the orchestrator log. Check out the branch and run the checks.
   - For each fix: if it resolves the finding, resolve the thread; if not, reply explaining why.
   - Check the fix commits for regressions.
   - Flag new issues with the same severity format.
   - Don't re-raise rebutted items unless you can refute the rebuttal with evidence.
   Orchestrator:
   - No BLOCKER/MAJOR remaining → skip to step 8.
   - Any BLOCKER remaining → stop and report to me. No round 3.
   - MAJORs only → step 7.

7. Fixer (round 2): same as step 5, including the test-request loop.

8. Verifier: check out the branch and run the checks. Confirm every round-2 finding has a reply, and every claimed fix SHA exists and addresses its finding. Make no changes. Return anything unresolved.

## Final summary
- PR link
- Per round: findings raised / fixed / rejected, by severity
- Test requests made and whether they were filled
- Unaddressed MINOR/NIT items
- Open questions from the plan
- Anything the verifier flagged
- Anything left for me to decide
- Path to the orchestrator log