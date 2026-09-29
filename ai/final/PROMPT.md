# Build `final`: the harmonized Nuuly Inventory API

Build the `final` branch of https://github.com/kgtech/nuuly-take-home. It is one build that keeps what the two earlier builds learned, and splits the API by version:
- The four operations in the take-home spec stay unversioned and match the spec.
- Everything the spec did not ask for lives under `/v2`: idempotency keys, page-size control, SKU details, and any later addition.

The run has two parts:
- **Part 1** (setup, preflight, study, plan) ends at a plan gate. There you stop and wait for the owner's approval.
- **Part 2** (build, critique, report) runs to the end without stopping.

This prompt is the whole job. There is no later prompt per issue.

## Words used here
- **Builds.**
  - **Build v1** is the build on `main`.
  - **Build v2** is the build on branch `v2`, which shares no history with `main`.
  - **The final build** is this one, on branch `final`.
- **API versions.**
  - **Unversioned API** means the spec's four operations at `/inventory…`.
  - **`/v2` API** means everything under `/v2/inventory…`.
  - In a sentence about paths, "v2" always means the `/v2` API, never build v2.
- **Owner** is the person who approves the plan. **Spec** is `docs/NUULY-ASSESSMENT-README-JUL-2026.md`.
- **Decision IDs** (G1, C3, A33, E1, …) are cards on the decision board, whose export is `DECISIONS.md`. The exception is build v2's A-series entries that #87 never put on the board (for example A19 and A21–A28): those live only in build v2's `DECISIONS-ADDED.md`. New cards for this build use the `H` series.
- **The q=0 tie-break.** When an Accept header lists equally specific ranges that are compatible with JSON, build v2 decides by the first such range; main's C3 amendment decides by the highest q.

## Owner decisions (already made)
These are settled. Don't reopen them. Turn each one into a board card that records the owner's choice (see "Plan gate").

| ID | Decision |
|---|---|
| OD-1 | `final` starts from the tip of `origin/v2` and keeps its history. |
| OD-2 | Path versioning. The unversioned API is the spec, and every extension lives under `/v2`. A future incompatible change gets a new prefix (`/v3`); `/v2` only grows compatibly. Both versions read and write the same stock. |
| OD-3 | Idempotency-Key handling moves to `/v2`, because the spec never had it. |
| OD-4 | `/v2` stock writes require an `Idempotency-Key`. An unversioned POST that carries the header, with any value including an empty one, gets 400 "Invalid request" and changes nothing. |
| OD-5 | The unversioned `GET /inventory` keeps a fixed page of 250. C2's reason still holds: an unbounded list failed with OutOfMemoryError at 1M SKUs (C-02). Its only query parameter is `after`, and `Link: rel="next"` points back to `/inventory?after=…`. Page-size control (`limit`) is `/v2` only. This is the one recorded deviation from the spec on that path. |
| OD-6 | The `/v2` routes mirror the spec operations at the same relative paths, and SKU details move to a sub-resource, `PUT /v2/inventory/{skuId}/details`. Build v2's `POST /v2/inventory/{skuId}` (create with details, 201/409) and `PUT /v2/inventory/{skuId}` are removed, together with the 409 text. |
| OD-7 | The React front end stays and calls only `/v2`. No new UI features. |
| OD-8 | Plan gate, then autonomous. |
| OD-9 | The decision board stays the source of truth for service and API decisions (S9). `DECISIONS.md` and `CLAUDE.md` are regenerated from it and never hand-edited. The front end keeps `frontend/DECISIONS.md`. |
| OD-10 | Final's board is a new private artifact, seeded with a copy of the current decisions database. Main's board artifact and its database are only ever read, never written, so main's published board keeps matching main's committed exports. |
| OD-11 | `PUT /v2/inventory/{skuId}/details` supports `If-None-Match: *` (RFC 9110): it answers 412 when the SKU already exists. The front end's Create page always sends it, so creating never overwrites an existing SKU's details. |

## Target API
All error bodies are `text/plain`. They use G6's fixed texts ("SKU not found", "Insufficient inventory", "Invalid request", "Internal server error") plus the 412 text below. Requests outside the spec's operations keep the standard behavior of G10 and T3.

### Unversioned (the spec)

| Operation | Behavior |
|---|---|
| `GET /inventory` | 200 `InventoryItem[]`, sorted by `skuId` (COLLATE "C"), at most 250.<br>Optional `after`: an exclusive cursor, compared as a plain string, never validated. `limit` and any other parameter are ignored.<br>When more rows exist, the response has `Link: <…/inventory?after=…>; rel="next"`, built from the routed path (C2) and carrying only `after`.<br>400 "Invalid request" when the query string can't be decoded or `after` is repeated (Z3). |
| `GET /inventory/{skuId}` | 200 `InventoryItem`. 404 "SKU not found", also for a skuId that fails G11. |
| `POST /inventory/{skuId}` | Adds stock, creating the SKU if needed. 200 `InventoryItem`; 400 "Invalid request".<br>A request that carries `Idempotency-Key` gets 400; nothing is written or stored. |
| `POST /inventory/{skuId}/purchase` | 200 with the remaining `InventoryItem`; 400 "Insufficient inventory" or "Invalid request"; 404. Same `Idempotency-Key` rule. |

Check order on both unversioned POSTs: body validation (`@Valid`), then `Idempotency-Key` present, then skuId (G11), then the write.

**Hardening stays as build v2 has it.** These checks answer only with status codes the spec lists:
- the skuId pattern (G11)
- strict JSON (G13)
- 400 instead of 415 (G3)
- POST Accept handling (U2, Y1, C3)
- `;` in the skuId segment
- the body cap (A19)
- Tomcat-level text/plain errors and TRACE (C1)

The spec's OpenAPI doesn't document them; the README's "Assumptions" and `openapi-v2.yaml` do.

**Freeze the rules, not the route list.**
- Apply every check above to every `/v2` route too, including `…/purchase` and `…/details`. Build v2's guard filter skips `/v2` purchase and checks PUT only on two-segment paths, so a `;` skuId would otherwise reach a keyed write, repeating lesson L21.
- Add no new kinds of check.
- Leave the known divergences (M-13, the q=0 tie-break) as build v2 has them unless the critique rates one MAJOR or worse. Where C3's text disagrees, the frozen behavior wins, and C3's card changes to match.

### `/v2`
Every `/v2` response body uses the v2 representation, `SkuItem` (`skuId`, `quantity`, optional `details`).

| Operation | Behavior |
|---|---|
| `GET /v2/inventory` | 200 `SkuItem[]`, at most 250.<br>`limit` (1–250, default 250, lenient as in R4) and `after`. `Link` points to `/v2/inventory` and carries both.<br>400 as in Z3. |
| `GET /v2/inventory/{skuId}` | 200 `SkuItem` with `ETag` (the details version, `"0"` before any details) and `Cache-Control: no-store`. Conditional-GET headers are ignored, as in build v2. 404. |
| `POST /v2/inventory/{skuId}` | Adds stock, creating the SKU if needed. Body `InventoryQuantity`. `Idempotency-Key` is required (UUID).<br>200 `SkuItem`.<br>400 "Invalid request" for: a bad body; a missing or malformed key; a key reused for a different request or older than 24h; an invalid skuId; overflow.<br>A repeated key with the same request replays the stored status, Content-Type and body. |
| `POST /v2/inventory/{skuId}/purchase` | Same key rules. 200 `SkuItem`; 400 "Insufficient inventory" or "Invalid request"; 404, also for an invalid skuId. |
| `PUT /v2/inventory/{skuId}/details` | Body `SkuDetails`, with build v2's field rules. Optional `If-Match` (strong tags or `*`) or `If-None-Match: *` (OD-11).<br>If the SKU doesn't exist, it is created at quantity 0 with these details: 201 with `ETag: "1"`.<br>If it exists, its details are replaced: 200 with the new `ETag`.<br>412 "Details changed since you read them. Reload the SKU and retry with its new ETag." when a precondition fails (RFC 9110). That covers: `If-Match` doesn't match; any `If-Match` on a SKU that doesn't exist yet; `If-None-Match: *` on a SKU that already exists.<br>400 "Invalid request", also for an invalid skuId.<br>Never changes stock. `Idempotency-Key` is ignored here, because PUT is idempotent by method. |

An invalid skuId (G11, including `;` content) answers 400 on `POST /v2/inventory/{skuId}` and on PUT details, and 404 on the GET and on purchase, as on the spec paths.

**Keys.** The keyed operations are `add` and `purchase`. Check order on the `/v2` POSTs: body, then key (missing or malformed gives 400), then skuId, then claim and write (A34). The request hash follows Y3.

At the gate, `board-cards.md` proposes two more things:
- Whether the hash input gains the API version. Recommended: yes, so a key stored by build v2's unversioned POSTs, which hashes the same inputs, never replays an `InventoryItem` body on `/v2`; such a key gets 400 instead.
- A V4 policy for the `'create'` operation and the 201/409 statuses that V3 allowed in the idempotency CHECKs. Stored rows are never purged (R9), so narrowing those CHECKs fails if any such rows exist.

### OpenAPI
- There are two springdoc groups, each exported and committed byte-stable:
  - `openapi.yaml`: the unversioned API, `info.version` 1.0.0.
  - `openapi-v2.yaml`: the `/v2` API, `info.version` 2.0.0.

  Swagger UI shows both.
- In `openapi.yaml`, restore the spec's summaries, descriptions and examples.
- C2's documented skuId schema (pattern, minLength, maxLength) moves to `openapi-v2.yaml` only. `openapi.yaml` shows the spec's plain `type: string`.
- A conformance test parses the YAML block in the spec and compares it with `openapi.yaml`.
  - Before comparing, it normalizes both documents: path-level `parameters` are pushed down to each operation, and `$ref: '#/components/schemas/Error'` is replaced by its definition.
  - The test lists the allowed differences, each with its decision ID:
    - the `openapi` version string;
    - generated `servers` and `tags`;
    - an `info.description` that states the deviations and points to `/v2`;
    - on `GET /inventory`: the operation's description and the 200 response's description, which must state the 250 cap and the `after` cursor; OD-5's `after` parameter; the `Link` header; the 400 response;
    - integer `format`s, if springdoc can't omit them.
  - Any other difference fails the test. It replaces build v2's `openapi-v1-baseline.yaml`.
- Grouped docs are served at `/v3/api-docs/{group}`. The export test (D7), the Gradle test inputs in `build.gradle.kts`, and the front end's `generate:api` and `check:api` scripts (FE23) all move to the two files.

## Invariants
Each needs a test that would fail if the invariant broke.
1. **No oversell.** Stock never goes below zero. When concurrent purchases through both versions ask for more than is in stock, exactly as many succeed as the stock allows.
2. **Exactly once on `/v2`.** The same key changes stock at most once and gets the same response, including when the repeats are concurrent.
3. **Recorded.** After every test that writes, `sku.quantity` equals the ledger SUM for every SKU (build v2's `Invariants` helper). The ledger stays append-only by trigger (A11).
4. **Spec conformance.** The conformance test passes.
5. **No leaks.** No unversioned request reads or writes the idempotency table. An unversioned POST that carries the header writes nothing.
6. **Version parity.** For every SKU, the unversioned and `/v2` reads return the same quantity. Walking both lists by their `Link`s visits the same `(skuId, quantity)` pairs in the same order.
7. **Bounded lists.** No list response holds more than 250 items.

## Setup (Part 1)
You may start in any checkout of the repository. Change nothing on GitHub before step 2.
1. `git fetch origin`. If a `final` branch exists locally or on the remote, stop and report; change nothing.
2. Create `final` from `origin/v2` as a normal branch, not an orphan.
   - Copy `ai/final/PROMPT.md` and `ai/final/lessons.md` from `origin/main`, or from the branch the owner names if they aren't on `main` yet.
   - If you can find neither file on a remote branch, stop and report.
   - Commit both and push `final`.
3. Make one docs-only commit that gathers every build's AI records under `ai/`:
   - Move build v2's run records into `ai/v2/` with `git mv`:
     - `PROMPT.md`, `lessons.md`, `issues.md`, `DESIGN-V2.md`, `DEVIATIONS.md` and `DECISIONS-ADDED.md` keep their names;
     - its final report becomes `ai/v2/REPORT.md`;
     - its run-records directory (the only hidden directory at the root besides `.git`) becomes `ai/v2/run-records/`;
     - its `DECISIONS.md` becomes `ai/v2/DECISIONS-v2.md`;
     - its hand-edited `CLAUDE.md` becomes `ai/v2/CLAUDE-v2.md`, renamed so no tool loads it as instructions.
   - Remove `spec/`, which is identical to the `docs/` copy below.
   - Copy from `origin/main`: `ai/` (except `ai/final/`), `agent-prompts.md`, and `docs/NUULY-ASSESSMENT-README-JUL-2026.md`.
   - Copy `ai/decision-board.html`, `DECISIONS.md` and `CLAUDE.md` from `origin/feat/issue-87-align-with-v2`. That board carries the E and A cards that record the balance row and the explicit key call, and those two files are its exports. The board and its exports now agree; the code doesn't fully follow them yet (see "Study").
   - Fix any relative links that break. Log each copy and move with its source.

## Preflight
Write each result to `ai/final/preflight.md`.
- **Tools.** Docker with Compose, JDK 25, and the Node version the front end needs. Ports 18080 and 15173 are free.
- **Baseline on the `final` tip.**
  - `./gradlew build` passes.
  - The front end's lint, typecheck, test and build pass.
  - `docker compose up --build` reaches readiness.

  Record test counts and durations.
- **GitHub.**
  - You can create labels, issues and PRs.
  - You can push a file under `.github/workflows/`. Test this by pushing a throwaway branch `final-preflight` that holds only a workflow file, then delete it.
  - You can merge your own PR. Test this by opening a PR from `final-preflight-a` into `final-preflight-b` (two throwaway branches off `final`), squash-merging it, then deleting both branches.

  Build v2's CI never ran because the token lacked the workflow scope, and a permission check blocked self-merge for about 35 minutes.
- **Board.**
  - Dump main's board database: the artifact and collection named in `ai/Prompt Template.md`, "Shared rules", board step 1. Read only.
  - Run `ai/export-board.mjs` on the board with the date in the `DECISIONS.md` header. Confirm the output equals `final`'s committed `DECISIONS.md` and `CLAUDE.md` (#87's exports) byte for byte.
  - Confirm you can publish a new private artifact with a database and write to that database (OD-10).

Anything that fails here is a blocker to raise at the gate, not something to work around.

## Study
Write `ai/final/current-state.md`:
- For every row of "Target API" and every invariant: where the `final` tip stands (with file references), what changes, and which tests move, invert or go away.
- Every rule in `CLAUDE.md` and every card whose wording ties an extension to the unversioned paths, with the card change each needs. At least G8, G9, G10, G14, R4, R8, Z3, C2, C3, S12, U3, Y1, G6, and build v2's A21–A28.
- Every card whose rule names a class, package, file, migration or test that differs on the `final` tip, with either a card change or a costed refactor PR. #87's cards describe #87's planned code, not build v2's, so check at least E1–E3, D5, D7, A37–A39, Z2, S4, S11 and T6. Prefer changing the card to match the code, unless the refactor is small or deletes code.
- Every live entry in build v2's `DECISIONS-ADDED.md` and every front-end decision the change touches (at least FE9 and FE23) that has no card on the #87 board, with a proposed card for each, or a note that an OD supersedes it. Front-end decisions change in `frontend/DECISIONS.md`, not on the board.

## Plan gate
Commit these to `final` (docs only), then stop and wait:
- `ai/final/preflight.md` and `ai/final/current-state.md`.
- `DESIGN.md` at the root: the final design. It covers storage, concurrency, idempotency, the versioning policy, failure behavior, and what changed from build v2 and why. Reuse build v2's text where it is still true. Build v2's design stays in `ai/v2/` as history.
- `ai/final/board-cards.md`: every new or changed card. For each: ID, question, options, the owner's choice (cite the OD) or your recommendation, and the `CLAUDE.md` rule text it generates. Mark every source and framework claim verified or unverified; unverified claims become the first tests.
- `ai/final/issues.md`: the issues, each with its acceptance criteria and the decision IDs it covers.
- `ai/final/plan.md`: each PR, the issues it covers, its order, one line on why it is grouped that way, the dependencies it adds (by name), and the build and test commands.

Then send the owner a message with:
- a short summary;
- the blockers that need the owner's action;
- any decision that OD-1 to OD-11 don't cover.

Wait for an explicit answer. Silence, an empty reply or "no preference" is not approval. Log the answer verbatim.

## Build (Part 2)
**Budget.** 5 hours of wall-clock time from the owner's approval.
- Start the self-critique no later than 3 h 15 min in.
- Keep the last 30 minutes for the report, the README and the `agent-prompts.md` entries.
- If time runs short, cut in this order: MINOR fixes, then the re-check, then the depth of interview prep. Never cut the report.
- When the budget runs out, stop cleanly: push what is green and write the report.

**No questions.** Nobody answers questions in Part 2. Where you'd normally ask, make the most reasonable choice within the approved plan, log it with one line of reasoning, and continue.

**Unworkable decisions.** If an approved decision turns out to be unworkable, record it in `ai/final/deviations.md` (decision ID, what you did instead, the evidence) and continue. The board change waits for the owner.

**Order.** These constraints are fixed; plan the rest yourself.
0. Create the `[final]` issues from `ai/final/issues.md`.
1. The board PR merges first. It:
   1. Publishes final's board as a new private artifact (a publish without a URL). It seeds the artifact's `decisions` collection with a copy of the dump from main's database (OD-10), and changes the URL in final's copy of `ai/Prompt Template.md` to the new one.
   2. Runs the template's board steps 1–6 against final's artifact only, adding the approved cards and choices and regenerating `DECISIONS.md` and `CLAUDE.md`.
   3. Follows this prompt's rules in place of the template's decision queue, "Never merge", branch naming and "ask me".
   4. Points the README at the new URL.

   If the artifact can't be published or written, keep the choices as JSON files in the dump format under `ai/final/board-db/`, export from them, and log it in `deviations.md`. From then on, the code follows the regenerated rules.
2. The CI workflow merges before any code PR. It covers the service and the front end, and runs on pushes to `final` and PRs into `final`. Start from `ai/v2/run-records/ci-workflow.yml`, the workflow build v2 parked because it couldn't push it.
3. Contract before consumers: a front-end end-to-end test waits until the service PR it calls has merged.

## Rules
**Precedence** when sources disagree, highest first:
1. The spec, with OD-5's deviation and the frozen hardening.
2. OD-1 to OD-11.
3. This prompt's "Target API", "Invariants", "Order" and "Rules".
4. Approved cards (`DECISIONS.md`, `CLAUDE.md`).
5. `DESIGN.md`.
6. The lessons.

Until the board PR merges, `CLAUDE.md` is #87's export and is stale wherever it disagrees with this prompt. Examples: G6 lists four texts with no 412, G8 says the key is optional on the spec POSTs, and T6 sets its own build order and stop hour. This prompt wins; log each conflict you act on.

**Tests and claims**
- **Tests first.** For each acceptance criterion, a test fails for the right reason before the code that satisfies it exists. A test subagent writes tests. Implementers never edit files under the test tree; if one needs a test changed, the test subagent changes it and you log why.
- **Verify claims first.** Before planning on framework or runtime behavior, prove it with a test or a probe. Unchecked claims were wrong in 6 of 9 build v1 runs.
- **Move tests, don't copy them.**
  - Keyed tests and paging tests that belong to `/v2` now are moved there.
  - The unversioned side keeps one test per new rule: header rejected, `limit` ignored, `after`-only Link.
  - One validation matrix per rule, at the lowest layer that proves it. HTTP tests take one representative row per outcome.
  - Every `@SpringBootTest` class uses the shared `@IntegrationTest` annotation and the one shared Postgres container.
- **Lessons become guards.** Each lesson in `ai/final/lessons.md` marked "guard" gets a test or an ArchUnit rule, named in that file. ArchUnit is approved as a test-only dependency.

**Scope and changes**
- **Scope.**
  - No new edge-case hardening unless a finding shows wrong stock, lost data, a security hole, or a status code outside the spec's list.
  - Make no performance claim without a benchmark recorded in the README.
  - Out of scope: Redis, messaging (#85), stored outcomes (#83), authentication, `X-Forwarded-*`, servlet-path support, new UI features.
  - List anything deferred in the README's "Designed, not built" with its decision ID.
- **Migrations.** Never edit V1–V3; they exist on the published `v2` branch. Add V4 and later only.
- **Decision records.** Never hand-edit `DECISIONS.md` or `CLAUDE.md`. Front-end choices go in `frontend/DECISIONS.md`, one line of reasoning each.

**Quality gates**
- **Green.**
  - Build, lint and the full test suite pass for the service and the front end.
  - For any change to runtime behavior, `docker compose up --build` also reaches readiness.
  - Push only green commits, and every commit must build and pass on its own. The one exception is a branch's first push holding only failing tests and stubs.
- **Stuck.** After 3 distinct attempts at the same problem, each a different hypothesis about the cause: log it, leave the work clearly marked, move on, and come back at the end.
- **Review.**
  - Before merging, a fresh subagent reviews each PR against its issues. In round 2 it also checks the round-1 fix commits for regressions (3 of 4 round-2 findings in build v1 were regressions).
  - Fix every BLOCKER and MAJOR before merging.
  - Severities are BLOCKER, MAJOR, MINOR and NIT. DECISION CHALLENGE is used instead of a severity when a rule or an approved decision is itself wrong. A DECISION CHALLENGE isn't fixed; it goes in the report for the owner.
- **Merge.**
  - Squash-merge your own PR into `final` once CI is green and no BLOCKER or MAJOR is open.
  - Never force-push and never rewrite `final`. Push only to `final`, `final-<slug>` branches and the `final-preflight*` throwaway branches; never push to a branch that existed before the run.
  - If the environment refuses a merge, don't re-issue the command. Keep working on a local integration branch and report it.

**GitHub**
- Issues: title prefix `[final]`, label `final` (plus `frontend` where it applies). Create the labels if they don't exist.
- Branches: `final-<slug>`. `final/<slug>` can't exist while a branch named `final` does.
- PRs: base `final`, title prefix `[final]`, one `Closes #<n>` line per issue. `final` isn't the default branch, so close each issue yourself after the merge.
- Reviews: post as COMMENT, since you can't approve your own PR. Check for a PENDING review before starting one.
- Don't comment on or change any issue, PR or branch that doesn't belong to `final`.
- Keep the text concise and professional: what changed; severity and finding; verdict with SHA. No narration.

**Logging.** Append to `ai/final/log.md` as you go, never after the fact. Each entry gets a UTC timestamp and one tag: `[setup]`, `[study]`, `[gate]`, `[board]`, `[service]`, `[frontend]` or `[critique]`. Log:
- the start and end of each PR and each phase;
- each subagent: its role, what you asked, and its result;
- each decision made without the owner;
- each stop, retry, CI failure and deviation;
- each copy from another branch: source path, destination, and whether it was copied as is or changed.

## Front end
- Every call goes to `/v2`. Generate the API types from `openapi-v2.yaml` only, so a call to an unversioned path fails the type check.
- Add stock and purchase send an `Idempotency-Key` created once per user action and reused on retry. Keep build v2's rule for when a key is kept or dropped (FE9).
- Create page:
  - `PUT /v2/inventory/{skuId}/details` with `If-None-Match: *` (OD-11). 201 on success. A 412 means the SKU already exists; show that, and link to the SKU's edit page.
  - Then, if the initial quantity is above 0, a keyed `POST /v2/inventory/{skuId}`.
  - If the add fails, show the server's error and offer a retry, keeping or dropping the key as FE9 says. The SKU exists at 0 stock until the add succeeds.
- Edit page: `PUT …/details` with `If-Match`.
- Playwright against the real service covers: create with initial stock; a create for an existing SKU that changes nothing; add then purchase; a double-submitted purchase that changes stock once.
- Keep the existing views, states, accessibility and phone-width behavior. The gate is `lint`, `typecheck`, `test`, `build`, `test:e2e`.

## Self-critique and fix
Run it when every planned PR has merged and `final` is green, or at 3 h 15 min, whichever comes first.

### Reviewers
Fresh subagents that wrote none of the code, running in parallel, one per area.
- **What each gets:** the spec; this prompt's "Owner decisions", "Target API" and "Invariants"; `DECISIONS.md`, `CLAUDE.md`, `DESIGN.md` and `frontend/DECISIONS.md`; the code at the current `final` SHA.
- **What each doesn't get:** the log, PR threads, or your reasoning.
- **What each may do:** change no files, but run the test suite, start the compose stack and send requests to it. Build v2's reviewers couldn't do this, and a stale-count bug got past them that Playwright then found.

Areas:
1. **Spec and versioning.** The unversioned API is the spec plus OD-5 and the frozen hardening, and nothing more. Each `/v2` twin matches its spec operation plus the documented extensions. No extension leaks onto an unversioned path.
2. **Concurrency and data integrity.**
   - stock writes through both versions at once
   - keyed repeats
   - the details PUT racing an add or a purchase on a new SKU
   - the ledger and balance invariants
3. **API contract and errors.** The code matches both OpenAPI files. Errors are text/plain everywhere. The key rules hold on both versions.
4. **Tests.** Tests hit real SQL, can fail, aren't duplicated across layers, and were moved rather than copied.
5. **Front end.** `/v2` only, key reuse on retry, the two-step create, states and accessibility.
6. **Security and operations.**
7. **Interview defense.** Cover each non-obvious choice:
   - path versioning
   - rejecting the key on unversioned POSTs
   - the 250 cap on a spec path
   - required keys
   - mirror routes
   - PUT creating a SKU, guarded by `If-None-Match: *`
   - the board

   For each, write the question a senior interviewer would ask, and check whether the code and records answer it with a reason, a rejected alternative and the tradeoff accepted.
8. **Industry standards.** Cite a source for every claim, or mark it UNVERIFIED. Cover:
   - API versioning: Stripe, GitHub's REST API versions, Shopify's Admin API versions, Google AIP-185.
   - Idempotency keys: the IETF Idempotency-Key draft and Stripe on required keys, reuse with a different request, and concurrent first requests, comparing the status codes they use with this build's 400.
   - Pagination and error-format conventions.
9. **Simplification.** What can be deleted without breaking an invariant, the spec or an OD. Each finding names the code to delete and the test that proves nothing is lost. In build v2, the big simplifications (Redis, the AOP interceptor) came from the owner, not the critique. This area exists to find the next one first.

### Findings, verification and fixes
- **Findings.** Each has:
  - an ID `F-<area>-<nn>`, a severity and a location;
  - what's wrong, with a failure scenario or the interview question it can't answer;
  - evidence: a code reference, a repro command and its output, a test, or a source link.
- **Verify.** One more fresh subagent merges the findings and removes duplicates. It reproduces every BLOCKER and MAJOR against the running stack, Playwright included, and marks each CONFIRMED, PLAUSIBLE or REJECTED with the reason. Save the result as `ai/final/critique.md`.
- **Fix.**
  - Fix every CONFIRMED or PLAUSIBLE BLOCKER and MAJOR, then MINORs as the budget allows. Fixes follow the same rules as the build, in PRs titled `[final] fix: …` that list the finding IDs.
  - For an interview-defense or simplification finding, prefer deleting code over recording a justification when an interviewer would find the deletion more convincing.
  - A standards finding is fixed by aligning with the standard, or by recording the reason with its source. A change to the spec's contract or to an OD is a DECISION CHALLENGE for the owner.
  - Record every outcome in `ai/final/critique.md`: fixed (with PR), recorded (with card or line), won't fix (with reason), rejected, or owner decision needed.
- **Re-check.** After the fixes merge, run the verifier once more, on the fixed findings only. Anything still failing goes in the report as open.
- **Interview prep.** Write `ai/final/interview-defense.md`: each question with a short answer grounded in the final code and records, plus the three weakest points of the build and how to answer if asked about them.

## Done means
- **Build and tests.** `final` builds. Lint, the full service suite (invariants included) and the front end's gate pass locally, and CI on `final` is green.
- **API.** The unversioned API passes the conformance test, the `/v2` API matches "Target API", and every invariant's test passes.
- **Decisions.** `DECISIONS.md` and `CLAUDE.md` are regenerated from final's board artifact, and nothing was hand-edited. Main's board artifact is unchanged.
- **Issues.** Every issue exists as a `final` issue and was closed after its PR merged.
- **Nothing else changed.** `main`, `v2` and every other branch, issue and PR are as they were before the run.
- **README.** It explains how to run the service, the front end and all tests. It has:
  - an "API versions" section: the OD-2 policy, what each version offers, that unversioned POSTs aren't retry-safe and reject the key, and how to reach every SKU through each version;
  - a "Try it" walk-through for both versions;
  - the assumptions, the deviations, and "Designed, not built".
- **Prompt log.** `agent-prompts.md` has an entry for this run (D0), plus short pointer entries for build v2's runs and the #87 run, which have none yet.
- **Run records.** `ai/final/` holds the prompt, lessons, preflight, current state, board cards, plan, log, critique, interview defense and report.

## Final report
Write `ai/final/report.md` and link it from the README. It covers:
- The plan as executed: each PR, its issues, and how it differed from `ai/final/plan.md`.
- What was built, by issue and PR, under service, front end and docs.
- Cards added and changed, deviations, and DECISION CHALLENGEs waiting for the owner.
- What changed from build v2 and why, and what was reused from build v1 and #87.
- How each invariant is shown, with its test.
- Decisions made without the owner.
- Self-critique results by area and severity, with outcomes and a link to `ai/final/critique.md`.
- Industry standards: where the build matches, where it differs on purpose, and which claims are UNVERIFIED.
- Anything unfinished or stuck, and known risks: where you are least confident the code is correct.

## Lessons, summarized
The full harmonized list, with where each lesson is enforced, is in `ai/final/lessons.md`. These are the ones that shaped this prompt:
- **Gate on the plan.** Owner overrides at the plan gate cost 0 rework commits; changes after the verifier cost 1 to 8 commits each.
- **Verify framework claims before planning on them.** Prove the uncertain ones with a test first.
- **Preflight what the environment allows** (CI workflow scope, self-merge, board access) before promising it.
- **Keep scope honest.** Build v1's estimate grew from 23 h to 45 h against a 16 h budget as paging, idempotency and HTTP edge cases were added.
- **Turn lessons into guards.** Lessons written as text were still repeated: raw-URI matching in a new filter, and duplicate matrices after copying tests.
- **Run the thing.** Compose and Playwright found defects that code-reading reviewers missed.
- **Ask the critique for deletions.** The critique justified complexity instead of cutting it.
- **Bound every list.** Never return an unbounded list, and build `Link`s from the routed path.
- **Keep the test suite lean.** Share one Postgres container and one test annotation, and don't repeat a validation matrix at every layer.
- **Keep every push green.** Push only green commits, add CI before the first code PR, and stop and propose when a plan is wrong.
