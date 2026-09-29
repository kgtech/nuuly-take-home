# Inventory front end

Single-page app (React 19, TypeScript strict, Vite) for listing, viewing, creating and editing SKUs and adding and purchasing stock through the Inventory API in `../openapi.yaml`.

Routes (hash router): `#/` list, `#/new` create a SKU with details (v2), `#/sku/:id` one SKU with its details, add stock and purchase, `#/sku/:id/edit` edit the details (v2, `If-Match`), `#/add` add stock to any SKU (`/v2`, creates it).

## Run

    npm install
    npm run dev          # http://localhost:5173, proxies /v2 to http://localhost:8080 (override: VITE_PORT, API_URL)

Start the service first (`docker compose up --build` in the repo root).

## Scripts

| Script | What |
|---|---|
| `npm run generate:api` | Regenerate `src/api/schema.d.ts` from `../openapi.yaml` (also runs before build and test) |
| `npm run typecheck` | `tsc -b --noEmit` |
| `npm run lint` | ESLint (typescript-eslint, react-hooks, jsx-a11y) |
| `npm test` | Vitest + React Testing Library + MSW |
| `npm run build` | `tsc -b && vite build` |
| `npm run check:api` | Regenerate the types and fail on a diff, proving `schema.d.ts` matches `openapi.yaml` |
| `npm run test:e2e` | Playwright against the real service through the dev proxy (`npx playwright install chromium` once); `e2e/details.spec.ts` needs the v2 endpoints |

Design choices are in `DECISIONS.md`.
