# Preflight (final)

Run on the `final` tip (be07ff2, after the ai/ reorganization), 2026-09-29, macOS arm64.

## Ports
The owner said to use the ports main uses. Main publishes the app on **8080** (`compose.override.yaml`) and has no front end, so the front end uses Vite's default **5173**. The `final` tip defaults to 18080 and 15173 (both overridable: `APP_PORT`, `VITE_PORT`, `API_URL`). 18080 is held by a container from another checkout (`nuuly-v2-app-1`), which is not ours to stop. 8080 and 5173 were free. **Proposal:** switch the defaults to 8080 and 5173 in the CI/compose/Vite/Playwright config and in ComposeFilesTest (one line each); until then every command sets the two env vars.

## Tools
| Item | Result |
|---|---|
| Docker + Compose | Docker 29.4.0, Compose v5.1.2, daemon reachable (OrbStack) |
| JDK | Host has JDK 21 only (sdkman, act cache). The catalog asks for 25 (`java = "25"`); `./gradlew build` still passed, so Gradle resolved a 25 toolchain. CI must provide JDK 25 (setup-java). |
| Node | v26.8.2, npm 11.19.1. `frontend/package.json` has no `engines`; lint, typecheck, test and build pass on it. CI pins Node 22 or 24 LTS: **unverified**, first CI run proves it. |
| Ports 8080, 5173 | free (18080 taken, see above) |

## Baseline on the `final` tip
| Check | Result |
|---|---|
| `./gradlew build` | passes. 747 tests, 0 failures, 0 skipped (sum of suite times 12 s; wall 21 s with the Gradle daemon warm). |
| Front end `npm ci`, `lint`, `typecheck`, `test`, `build` | all pass. 14 files, 227 tests. Wall 23 s including `npm ci`. |
| Front end `test:e2e` | not run in preflight (needs the stack); it is the gate in Part 2. |
| `docker compose up --build` (project `final-preflight`, `APP_PORT=8080`) | app and Postgres reach readiness; `/actuator/health/readiness` 200, liveness UP. Stack removed with `down -v`. |

## GitHub (account kgtech, repo permissions admin/push)
| Check | Result |
|---|---|
| Labels, issues, PRs | Permitted (repo admin). Existing labels include `frontend`; `final` does not exist yet and will be created at step 0. |
| Push a file under `.github/workflows/` | **BLOCKER.** Rejected: "refusing to allow an OAuth App to create or update workflow `.github/workflows/preflight.yml` without `workflow` scope". Token scopes: gist, read:org, repo. Same failure as build v2. **Owner action:** `gh auth refresh -h github.com -s workflow`. Without it the CI PR cannot merge as specified. |
| Self-merge | Works. PR #89 (`final-preflight-a` into `final-preflight-b`) squash-merged with no wait; both branches deleted. The throwaway branch `final-preflight` from the workflow test never reached the remote. |
| Footprint | PR #89 (merged) remains in the repo's PR list; it can't be deleted through the API. |

## Board
| Check | Result |
|---|---|
| Dump of main's board DB (artifact Ma9JpFCmsLHQpJgWXbwpBT, collection `decisions`) | 80 documents, read only |
| `ai/export-board.mjs` on the board, date 2026-09-29 | `DECISIONS.md` and `CLAUDE.md` are **byte-identical** to final's committed files (#87's exports) |
| Publish a new private artifact with a database, write to it | Works: https://claude.ai/artifact/Fw2KtSt1EGCcQ2nQpL5Yfm (`db` and `user` capabilities), doc `decisions/PROBE` set and listed. This is a throwaway probe; deleting an artifact needs the owner's confirmation, so it is listed for the owner. The real board artifact is published at the board PR. |

## Blockers for the gate
1. GitHub token lacks the `workflow` scope (CI workflow can't be pushed).
2. Port defaults: confirm the switch to 8080 and 5173 (see Ports).
3. Note: the catalog and Dockerfile need JDK 25; the host only has 21. Gradle handled it; no action unless the build fails on another machine.
