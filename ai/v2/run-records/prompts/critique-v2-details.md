# Self-critique of the v2 details run (issues #71–#73, PRs #74 and the front-end PR)

Four fresh reviewers, one per area, each given the reviewer template (`critique-reviewer.md`) with these area instructions. Scope: the diff between v2 9afd14a (before the run) and the current v2 tip, read in the context of the whole build. Findings use the prefixes below.

## spec (prefix `spec`): spec and contract
The four spec operations must be byte for byte as before (`src/test/resources/openapi-v1-baseline.yaml` against `openapi.yaml`); v2 (`DESIGN-V2.md` §8) is additive. Check the v2 OpenAPI against the code (codes, text/plain errors, ETag and If-Match, schemas and their required lists), G6/S5/T3 on every v2 error, G11 on each v2 operation, G13 (unknown properties, coercion), the request guard and the Accept filter on `/v2`, the 32 KB cap, the `Link` on the v2 list, and that `openapi.yaml` was regenerated (ApiDocsTest) and the generated front-end types match it (`npm run check:api` is described, not run).

## conc (prefix `conc`): concurrency and idempotency
DESIGN-V2 §1–§4 invariants under the new create path (atomicity, the same stock path and ledger for initial stock, lock order sku → sku_details → ledger, concurrent create vs create and create vs spec add, post-commit cache refresh), the widened `@Idempotent` contract (A29: v1 hash bytes unchanged, the create fingerprint canonical and collision-free), 201/409 stored and replayed byte for byte including from Redis, validation 400s never stored, PUT's version re-check inside `DO UPDATE … WHERE` under concurrent conditional PUTs, 404 vs 412, and that a PUT never touches stock.

## fe (prefix `fe`): front end
Issues #72 and #73 acceptance criteria: the create page makes one `POST /v2` with an Idempotency-Key under FE9; the edit page uses PUT with If-Match and handles 412/404; the SKU and list views read v2; every local block shows a field-specific reason and never the server's text; buttons are `aria-disabled` with visible helper text linked by `aria-describedby`; in flight uses `aria-busy` and read-only inputs; one outcome area on the SKU page; the re-fetch after "Insufficient inventory"; guidance lines per status class; "Request reference" details element; the list's cursor empty state and the per-page hint; generated types only; MSW mock fidelity to §8; Playwright coverage; axe and 375 px.

## intv (prefix `intv`): interview defense
For every non-obvious v2 choice (the /v2 path, 409 vs upsert, PUT vs PATCH and optional If-Match, text/plain errors, no Redis for details, the separate table, cost representation, 201, the widened idempotency contract, the front end's ETag handling and validation rules), write the interviewer's question and check whether `DESIGN-V2.md` §8, `DECISIONS-ADDED.md` A21–A29, `.fable/interview-defense.md` Q24–29, `frontend/DECISIONS.md` FE30+ and the README answer it with the reason, the rejected alternative and the accepted tradeoff, and whether the code contradicts the answer.
