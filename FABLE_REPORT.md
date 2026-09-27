# FABLE_REPORT: the V2 run

Autonomous build of V2 by the Fable model, 2026-09-27, from the warm-start package on `v2` (first commit 7fafbe2). Budget: 4 hours wall-clock from Setup (19:54Z). The log is `.fable/log.md`; the design is `DESIGN-V2.md`; the study of the first build is `.fable/current-implementation.md`.

## Work plan as executed

`.fable/plan.md` planned four PRs; three landed, the self-critique fixes are the fourth group.

| PR | Branch | Issues | Differences from the plan |
|---|---|---|---|
| #64 Service | `v2-service` | #35, #43–#46, #48, #49, #51–#53, #55, #57 | Branch names are `v2-<slug>` (see DECISIONS-ADDED A1). The CI workflow could not be pushed (DEVIATIONS.md). |
| #62 Front end | `v2-frontend` | #36–#42 | Built in parallel by a subagent as planned; its review found 3 BLOCKERs (form role, key lifecycle, malformed hash) fixed before merge. |
| PR3 Ops and remaining review requirements | `v2-ops` | #47, #50, #54, #56, #58, #59, #60 | Planned as docs/ops only; it also took #54 (raw `;` and Accept q=0), #59 (single outcome mapping), #60 (shared test annotation) and #56 (24h boundaries) because they were small once the service existed. |
| Fixes | `v2-fix-*` | critique findings | {{FIXES}} |

Not done: #61 (test-suite de-duplication of the copied validation matrices), see "Unfinished".

## What was built

**Service (issues #43–#53, #55, #57):** the four spec operations with text/plain errors, G11 SKU IDs, G13 strict JSON, paging with `Link` and a default page of 250, the optional `Idempotency-Key`, actuator health with liveness/readiness, springdoc export (`openapi.yaml` byte-identical to the first build's), compose with Postgres and Redis on loopback, Dockerfile with heap policy and a readiness healthcheck. The web layer is reused from `main`.

**Storage (issue #35):** `DESIGN-V2.md`. Balance rows with conditional UPDATEs at READ COMMITTED; append-only ledger enforced by trigger; Postgres idempotency claim with a Redis replay copy; Redis cache of read counts with versioned refresh and a TTL bound.

**Front end (issues #36–#42):** `frontend/`, React 19 + TypeScript strict + Vite; generated API types; list with Link paging, view, add, purchase; Idempotency-Key once per action; errors verbatim; Vitest + RTL + MSW; Playwright against the real service. Choices in `frontend/DECISIONS.md`.

## Decisions added

`DECISIONS-ADDED.md` (A1–A12) and `frontend/DECISIONS.md` (FE1–FE15).

## Decisions superseded and deviations

Superseded through `DESIGN-V2.md` §7: V1, D3, S1, D4, W1, W2, X1, Y2, G7, G12, parts of D5/D1/D9, Z1's isolation clauses, R2's 40001 clause, D8/S4 compose content, D10, and the test-suite items of issues #24/#26/#29/#30 written against `main`'s tests.
Deviations (`DEVIATIONS.md`): D0/S9 (no agent-prompts.md or board regeneration), D1/D3 (no JPA dependency), S11's `main`-specific items, the "decision change posted on the issue" clauses of #7 AC4 / #22 AC1, and the CI workflow that GitHub refused (token without the `workflow` scope).

## How V2 differs from `main`

See `DESIGN-V2.md` §6. In short: one row per SKU updated in place instead of a growing SUM; row locks instead of SERIALIZABLE and retries; the append-only rule in the database; hot reads from Redis; an idempotency fast path in Redis with Postgres as the authority. Tests for each invariant: no oversell (`InventoryConcurrencyTest`, `InventoryHttpConcurrencyTest`, `SchemaTest` CHECK), exactly once (`IdempotencyApiIntegrationTest`, `IdempotencyHttpConcurrencyTest`, `RedisFaultTest` through flush/pause/restart), durable (`RedisFaultTest#acknowledgedChangesSurviveARedisRestart`), recorded (`Invariants.balanceMismatches` in `InventoryServiceTest`, `CrossSkuConcurrencyTest`; the trigger in `SchemaTest`), bounded staleness (`StockCacheTest`), defined failure (`RedisFaultTest#everyOperationKeepsWorkingWithRedisDown`, one WARN per role).

## Benchmark

None. `DESIGN-V2.md` makes no speed claim (§6 "Performance").

## Decisions and assumptions made without a person

Listed with reasoning in `.fable/log.md` (entries marked "Decision") and `DECISIONS-ADDED.md`. The ones that shaped the run: branch naming (A1); keeping Z1's advice structure at READ COMMITTED (A10); writing the tests for the new storage in the same pass as the code rather than strictly first (logged as a rule departure at 20:33Z); resolving front-end review finding F-04 by PROMPT precedence (client validation blocks only what the server is certain to reject); treating the CI push refusal as a deviation rather than stopping.

## Reuse from `main`

Copied as is (logged at 20:22Z): the Gradle wrapper and settings, `.gitignore`/`.gitattributes`/`.dockerignore`, `Dockerfile`, `InventoryApplication`, `web/HttpConstants`, the whole `inventory/web` package (controller, advice, Tomcat valve, filters, `TextErrors`, `OutcomeResponses`, `StoredResponses`, `InventoryApi`, `InventoryQuantity`), `inventory/{InventoryItem, InventoryPage, SkuId, StockOutcome, WriteResult}`, the `idempotency` package except the interceptor and store, the test helpers `RawHttp` and `Concurrently`, and 27 test classes (contract, validation, advice, paging, Tomcat, TRACE, library paths, idempotency API/store/schema, HTTP concurrency, ApiDocs, compose files, package boundary, unit tests).
Copied then changed: `build.gradle.kts`, `gradle/libs.versions.toml`, `compose*.yaml`, `application.yaml`, `InventoryService` (rewritten around `StockRepository` and the cache), `IdempotencyInterceptor` and `IdempotencyStore` (READ COMMITTED, Redis fast path, after-commit copy), `InventoryController` (single outcome mapping), the copied tests' cleanup and seeding, `ComposeFilesTest`, `IdempotencyInterceptorTest`.
Rebuilt differently: everything under storage (`StockRepository`, `Balance`, migrations, the `cache` package, `ReplayCache`), the test infrastructure (one Postgres and one Redis container per JVM, `Tables`, `IntegrationTest`), the front end (new).

## Unfinished or stuck

{{UNFINISHED}}

## Self-critique

{{CRITIQUE}}

## Industry standards

{{STANDARDS}}

## Known risks

{{RISKS}}
