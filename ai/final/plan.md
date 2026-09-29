# Plan

Order is fixed by the prompt where it says so (board, then CI, contract before consumers); the rest is mine. Every PR: red test first (a test subagent writes tests; implementers don't touch `src/test`), green locally, fresh reviewer subagent, squash-merge into `final` after CI is green. Every commit builds and passes on its own.

Times are budget estimates in hours of wall clock from approval (5 h total; critique starts by 3 h 15 min; last 30 min for the report).

| # | PR (branch `final-<slug>`) | Issues | Why grouped this way | New dependencies | Est. |
|---|---|---|---|---|---|
| 0 | (issues and labels created, no PR) | all | Step 0 | none | 0.1 |
| 1 | `final-board` | F-01 | Merges first: everything after follows the regenerated rules | none (Playwright from a scratch dir for the export, not a repo dependency) | 0.4 |
| 2 | `final-ci` | F-02 | Before any code PR | GitHub Actions: `actions/checkout`, `actions/setup-java` (Temurin 25), `actions/setup-node`, `gradle/actions/setup-gradle`, `actions/upload-artifact` (by name) | 0.3 |
| 3 | `final-ports-archunit` | F-03 | Two small config changes both later PRs rely on | `com.tngtech.archunit:archunit` (test scope; version in the catalog) | 0.3 |
| 4 | `final-guard` | F-04 | Fixes L21 before the `/v2` write routes exist | none | 0.4 |
| 5 | `final-v2-writes` | F-05 | New routes, additive: unversioned side unchanged | none | 0.8 |
| 6 | `final-fe-stock` | F-06 | Consumer of PR 5; keeps the front end working when PR 7 rejects the key | none | 0.3 |
| 7 | `final-unversioned` | F-07 | Rejects the key and caps the list; deletes the unversioned keyed and `limit` tests that PR 5 re-created for `/v2` | none | 0.5 |
| 8 | `final-details-put` | F-08 | New route, additive: build v2's create and PUT stay until PR 10 | none | 0.6 |
| 9 | `final-fe-details` | F-09 | Consumer of PR 8 | none | 0.4 |
| 10 | `final-remove-create` | F-10 | Only safe once nothing calls the old routes; V4 rides with the removal | none | 0.4 |
| 11 | `final-openapi` | F-11 | Both contracts are final only after PR 10 | YAML parsing in a test: SnakeYAML (already on the Spring Boot classpath; version from the BOM) — verify, else Jackson YAML | 0.6 |
| 12 | `final-invariants` | F-12 | Needs both versions complete | none | 0.5 |
| 13 | `final-e2e` | F-13 | Front-end end-to-end waits for PRs 5, 8, 10 | none | 0.4 |
| 14 | `final-docs` | F-14 | README, DESIGN update, prompt log | none | 0.3 |
| — | critique, fix PRs, re-check, interview defense, report | — | Prompt's schedule | none | ~1.5 |

Order: 0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14 (PRs 3 and 4 can run in parallel with review of the previous one when files don't overlap). Estimates add to about 6.3 h of work; with the critique that is over the 5 h budget, so the cuts in order are: PR 14's extra polish, the re-check, interview-prep depth. If time runs out, PRs 12–14 shrink to the invariants that are already covered by tests in earlier PRs, and the rest goes under "Designed, not built".

Why PRs 5, 7, 8, 10 are split into "add" and "remove": the unversioned keyed tests and build v2's create tests are the only coverage of exactly-once and details behavior until the `/v2` versions exist; adding first keeps the suite honest and the front end green (the front end calls old and new routes over the sequence).

## Commands
Service (env only until PR 3 changes the defaults): `./gradlew build` (tests need Docker), one test: `./gradlew test --tests '<class>'`, docs export: part of `./gradlew test` (ApiDocsTest).
Front end (from `frontend/`): `npm ci`, `npm run lint`, `npm run typecheck`, `npm run check:api`, `npm test`, `npm run build`, `npm run test:e2e` (needs the stack: `APP_PORT=8080 docker compose up --build -d`, Vite on 5173).
Stack: `docker compose up --build`; readiness `curl localhost:8080/actuator/health/readiness`.
Board export: `node ai/export-board.mjs ai/decision-board.html <db-dir> <out-dir> <yyyy-mm-dd>` (Playwright package next to the script).
GitHub: `gh issue create`, `gh pr create --base final`, `gh pr merge --squash`.
