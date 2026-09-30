# Work plan

Budget 4 h from 19:54Z; self-critique starts by 22:54Z at the latest. Four PRs, in this order. Issue numbers are filled in once the `[v2]` issues exist (see `.fable/log.md`).

| # | Branch | Covers | Why grouped this way |
|---|---|---|---|
| PR1 | `v2/service` | All service stories from `issues.md` (#1 setup and schema, #2 stock writes, #3 the four operations and text/plain errors, #4 concurrency tests, #5 compose and health, #6 Idempotency-Key, #7 paging, #8 OpenAPI export and README) plus the review requirements #15, #21–#30 as they apply to V2, the storage-design issue (Postgres + Redis, invariants, fault tests), and the CI workflow | The web layer is reused from `main` nearly as is and the storage design is one coherent change; splitting it into eight PRs would only add merge overhead in a 4 h budget. Tests are written first per criterion inside the branch. |
| PR2 | `v2/frontend` | The front-end issues (list with paging, view one, add stock, purchase, idempotency key per action, errors and states, a11y and phone width, tests and tooling) | Independent of PR1: built from `openapi.yaml` with generated types and MSW; runs in parallel as a subagent. Playwright end-to-end tests are added in PR2 and executed against the real service once PR1 has merged (PR3 records the run). |
| PR3 | `v2/ops-docs` | README (run service, Redis, front end, all tests, assumptions and failure behaviour), compose hardening from #27 (loopback Postgres, heap policy, app healthcheck), Playwright run against the merged service, anything PR1/PR2 review deferred | Small, after both builds exist. |
| PR4+ | `v2/fix-...` | Self-critique fixes, grouped by area, finding IDs in the body | Per PROMPT "Self-critique and fix". |

Order: PR1 and PR2 in parallel (PR2 by a subagent), PR1 merges first, then PR2, then PR3, then critique, then fixes. If PR1 is not green by 22:30Z, the remaining service items move to "unfinished" and the critique starts on what is merged.

## v2 details run (2026-09-28, owner-approved after the UI review)

| # | Branch | Covers | Why grouped this way |
|---|---|---|---|
| PR5 | `v2-details` | #71 (service: V3 schema, `/v2/inventory` create/replace/read/list, ETag, 409/412, idempotent create, request guard, OpenAPI export, README). Its first commit is the design (DESIGN-V2 §8, A21–A29, openapi.yaml v2 paths, interview defense), committed before any code as the owner asked; the design is not merged on its own because a hand-written openapi.yaml would make ApiDocsTest fail on `v2` until the code lands. | One coherent change to schema, service and web layer; the front end builds from the committed openapi.yaml in parallel. |
| PR6 | `v2-details-frontend` | #72 (create and edit pages on v2, SKU and list views with details) and #73 (the UI validation review fixes) | Both touch the same forms and validation module; one PR avoids two conflicting edits of StockForm. Built by a subagent from PR5's design commit with MSW; Playwright runs against the merged service. |
| PR7+ | `v2-details-fix-…` | Focused self-critique fixes (spec and contract, concurrency and idempotency, front end, interview defense) | Per PROMPT "Self-critique and fix". |

Order: PR5 and PR6 in parallel, PR5 merges first, then PR6 (rebased on v2, Playwright run recorded), then the critique and fixes.
