# Critique — fe (Front end) — v2 @ d01852a (reviewer report, verbatim)

## Findings
- F-fe-01 BLOCKER — useIdempotentSubmit.ts:36 drops the Idempotency-Key on any non-zero status, including a proxy's own 502/504 (Vite proxy answers upstream errors with 502 and an empty body). Scenario: purchase commits, proxy resets before the body is relayed, browser sees 502, key dropped, user clicks again with a new key → stock decremented twice. Repro: Playwright `page.route` fulfilling the first purchase with 502 after `route.fetch()`, click again, read quantity (expected 7, actual 4; two different keys). Fix: keep the key for 5xx (and 408/429) and status 0; drop only on 2xx/4xx.
- F-fe-02 MAJOR — useIdempotentSubmit.ts:24-27: `crypto.randomUUID()` throws outside a secure context (LAN IP over http); `busyRef` is set before the call and never reset, so the form silently dies. Fix: `getRandomValues` v4 fallback; reset `busyRef` in `finally`.
- F-fe-03 MAJOR — AddStockPage.tsx:40 `key={skuId}` remounts StockForm on every SKU keystroke, wiping quantity and the last result (#38 AC3). The fingerprint already handles a changed skuId; drop the key.
- F-fe-04 BLOCKER (by rubric; #41 AC3/AC4) — no axe check; no 375 px scrollWidth assertion or screenshots; the CSS grep test only catches px values.
- F-fe-05 MAJOR — e2e double-submit test passes even if no Idempotency-Key is sent (busyRef swallows the second click; only request count asserted). Fix: assert the header matches the UUID regex; add a real retry flow (route.fetch then abort, click again, same key, quantity changed once).
- F-fe-06 MAJOR — styles.css dark mode: white on `#7fb0ff` ≈ 2.2:1, fails WCAG AA on every primary button. Fix: dark text on the accent or a darker accent.
- F-fe-07 MINOR — an empty error body renders an empty role="alert" (e.g. proxy 502). Fallback text such as `HTTP 502`.
- F-fe-08 MINOR — #36 AC3 (limit range not sent) knowingly not implemented (FE17) but not in DEVIATIONS.md; input has min/max while noValidate ignores them.
- F-fe-09 MINOR — `#/sku/.` and `#/sku/..` are URL-normalised (`new URL`) to `/inventory/` and `/`; wrong endpoints and messages. Pre-check GET like certainRejection or encode `.`.
- F-fe-10 MINOR — test gaps vs ACs: #37 AC3 (quantity after action in SkuView, RTL), #40 AC4 (key across SKUs/operations), #40 AC1 (Enter), #36 AC2 (exact Link URL requested), #38 AC3 (values kept), key-drop-on-4xx path.
- F-fe-11 MINOR — #42 AC2: no `git diff --exit-code` on schema.d.ts (pretest regenerates silently); no lint/grep against hand-written API types.
- F-fe-12 MINOR — `InventoryItem` fields optional in the generated type (openapi.yaml has no `required`); FE uses `?? ''`. Root cause is the service OpenAPI.
- F-fe-13 NIT — no aria-current on nav; Find form before h1; no table caption; nextPage focuses heading before load; README `npm install` vs `npm ci`; no production serving note; aria-invalid while typing.

## Checked and found met
Generated types everywhere (openapi-typescript 7.13.0; strict + noUncheckedIndexedAccess + exactOptionalPropertyTypes); four operations wired; Link paging per RFC 8288, re-rooted, limit/after only when set; key once per action, reused after status 0/timeout, dropped after 200 and on quantity change, one request per double click; errors verbatim on 400/404/418/500 in all views; loading/empty/error states with Retry; labels, aria-label on forms, live hints, focus on alert/status, lang; certainRejection mirrors G11/G13 exactly, noValidate; proxy only, no service change; phone-width CSS by inspection; Playwright both flows in desktop and 375×667; frontend/DECISIONS.md FE1–FE18.
