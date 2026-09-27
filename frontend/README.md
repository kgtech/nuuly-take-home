# Inventory front end

Single-page app (React 19, TypeScript strict, Vite) for listing, viewing, adding and purchasing stock through the Inventory API in `../openapi.yaml`.

## Run

    npm install
    npm run dev          # http://localhost:5173, proxies /inventory to http://localhost:8080

Start the service first (`docker compose up --build` in the repo root).

## Scripts

| Script | What |
|---|---|
| `npm run generate:api` | Regenerate `src/api/schema.d.ts` from `../openapi.yaml` (also runs before build and test) |
| `npm run typecheck` | `tsc -b --noEmit` |
| `npm run lint` | ESLint (typescript-eslint, react-hooks, jsx-a11y) |
| `npm test` | Vitest + React Testing Library + MSW |
| `npm run build` | `tsc -b && vite build` |
| `npm run test:e2e` | Playwright against the real service through the dev proxy (`npx playwright install chromium` once) |

Design choices are in `DECISIONS.md`.
