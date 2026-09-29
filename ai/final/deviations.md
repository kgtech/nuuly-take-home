# Deviations

Approved decisions or prompt rules that turned out unworkable, and what was done instead. The board change (if any) waits for the owner.

## D-1: CI workflow can't be pushed (prompt "Order" step 2, "Quality gates: Green", "Merge")
- **Evidence:** `git push` of `.github/workflows/ci.yml` (and of a throwaway workflow in preflight) is rejected: "refusing to allow an OAuth App to create or update workflow … without `workflow` scope". `gh auth status` shows scopes gist, read:org, repo. The owner's "approve" at the gate did not change the token.
- **What I did instead:** the workflow is committed at `ai/final/ci-workflow.yml` (start: build v2's parked workflow; adds branch `final`, the front end's `check:api`, an end-to-end job on the compose stack). `scripts/gate.sh` runs the same steps locally (`--e2e` adds compose plus Playwright); every PR is merged only after `scripts/gate.sh --e2e` passes on the branch, and the result is in the PR's review comment. I did not use a different credential to get around the scope check.
- **Owner action:** `gh auth refresh -h github.com -s workflow`, then `git mv ai/final/ci-workflow.yml .github/workflows/ci.yml` and push.
- **Consequence:** "CI on final is green" in Done means can't be shown; the local gate replaces it in the report.
- **Not run:** the parked workflow has never executed on GitHub, so it is untested there; issue #92's "green on the PR" criterion is unmet, not replaced. The issue stays open until the owner moves the file and a run is green.
- **Ports:** the gate uses main's ports (8080, 5173) by the owner's instruction (card H13); it fails fast if either is busy.
