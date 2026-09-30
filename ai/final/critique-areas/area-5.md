# Area 5 findings: front end (SHA 436cacae)

Method: real compose stack (crit5, app :18205) plus Vite dev server on :15205, shipped Playwright suite (34/34 pass, both projects), lint, tsc, vitest (248 pass), `npm audit` (0 vulnerabilities), and my own throwaway specs (saved in /private/tmp/claude-501/critique/s5/zz-crit*.spec.ts, screenshots in the same dir). Worktree removed, stack torn down.

### F-5-01 [MAJOR] A long unbroken name makes the SKU page scroll sideways (desktop and 375 px)
- Location: frontend/src/styles.css:48-52 (`h2` has no `overflow-wrap`; only `h1`, line 53, and a few classes have it); frontend/src/pages/SkuView.tsx:164 renders `<h2>{details.name}</h2>`.
- What is wrong: A legal name (1-120 chars, no spaces, e.g. a URL-like or code-like name) is rendered in the details card `h2` with no wrapping rule. The whole document becomes wider than the viewport, so the page gets horizontal scroll and the card content is off-screen. FE15/FE18/FE22 promise no horizontal overflow at 375 px, and the shipped axe/overflow tests only use short names. The same happens (smaller) with a 64-char SKU ID in `.kicker` on the Edit page at 375 px.
- Evidence: PUT details with name `'W'.repeat(118)` (or `<img src=x onerror=alert(1)>` + 90 W), then `document.documentElement.scrollWidth - innerWidth` on `#/sku/<id>`: chromium 1027, mobile-375 1780 (zz-crit4.spec.ts, zz-crit3.spec.ts; screenshot s5/q4-sku-longname.png). Offender found by scrollWidth scan: `H2 sw=1782 cw=301` inside `SECTION.card.details`. Edit page at 375 px with a 64-char SKU ID: `P.kicker sw=618 cw=343`, page overflow 259. The list page is fine (table sits in an `overflow:auto` wrapper), and the description is fine (`overflow-wrap:anywhere`).
- Suggested fix: add `overflow-wrap: anywhere` to `h2` and `.kicker` (or to `h1,h2,h3,.kicker`), and add a long-name/long-id case to the Playwright overflow test.

### F-5-02 [MINOR] The 412 headline on Create is server jargon that does not describe what happened
- Location: frontend/src/pages/CreateSkuPage.tsx:150-160 (headline is the server text verbatim); frontend/src/components/Messages.tsx ErrorText.
- What is wrong: A user creating a SKU that already exists (or who reloaded between step 1 and step 2 and pressed Create again) sees, as the bold first line, "Details changed since you read them. Reload the SKU and retry with its new ETag." followed by the friendlier EXISTS line. The user never read anything and there is nothing to reload; "ETag" means nothing to a shop user. FE33/FE11 chose verbatim server text on purpose, and FE34 already replaces it for the "lost" case, so the inconsistency is a choice, but the result is a contradictory message pair.
- Evidence: zz-crit.spec.ts P3 output after back/forward/reload and a second Create: `Details changed since you read them. Reload the SKU and retry with its new ETag. A SKU with this ID already exists. Open it to edit its details or add stock. Open Caj00j2z` (screenshot s5/p3-after-reload.png). Same jargon on the Edit 412 (s5/p3-412.png), where "Reload" is at least meaningful.
- Suggested fix: on a 412 from `If-None-Match: *` show only the EXISTS sentence as the headline (as done for `kind==='lost'`), or fix the service's 412 text to something valid for both cases.

### F-5-03 [MINOR] "Sending again is safe" is shown after a 5xx on the Edit save, where a resend is a 412
- Location: frontend/src/components/Messages.tsx:16-20 (`writeGuidance`), used at frontend/src/pages/EditSkuPage.tsx:129.
- What is wrong: For 0/408/429/5xx the guidance says "Sending again is safe: the same request will not be applied twice." On Edit there is no Idempotency-Key and the request carries `If-Match` with the old ETag; if the first save was applied but the response was lost, the second click returns 412 "Details changed since you read them...". FE37 admits "a retry is safe only after Reload", but the text shown says the opposite, and the create page has a special UNCERTAIN line for the same reason.
- Evidence: code reading (EditSkuPage failure branch uses `writeGuidance(failure.status)` for every non-412). Not exercised end to end (would need a proxy that drops the response after the server applies it). UNVERIFIED on the wire.
- Suggested fix: on Edit, for retryable statuses say "The save may or may not have been applied. Reload to see the current details."

### F-5-04 [NIT] Dev-only: StrictMode double effect steals initial focus into `<main>`
- Location: frontend/src/App.tsx:14-20 (`first` ref guard) with frontend/src/main.tsx:9 (`StrictMode`).
- What is wrong: In dev StrictMode runs the effect twice; the first run flips `first.current`, the second run focuses `<main>` on the initial page load. Keyboard users on `npm run dev` (the reviewer path) then skip the header and nav on their first Tab. A production build is not affected.
- Evidence: zz-crit.spec.ts P6: first Tab from a fresh load lands on "Back to inventory" (index 0), not the brand link or the nav. There is also no skip-to-content link, but the header is short so this is minor.
- Suggested fix: track the previous route in a ref and focus only when it changes, which is StrictMode-safe.

### F-5-05 [NIT] Unknown deep paths under `#/sku/` become SKU ids
- Location: frontend/src/hooks/useHashRoute.ts:16-20.
- What is wrong: `#/sku/x/edit/` (trailing slash) is parsed as a SKU page for id `x/edit/` and shows "SKU not found" plus add-stock forms instead of "Page not found". Harmless (nothing is sent), only confusing.
- Evidence: zz-crit2.spec.ts Q2 output: `trailing slash: BACK TO INVENTORY | SKU | x/edit/ | SKU not found | Add stock ...`.
- Suggested fix: reject ids containing `/` after decoding in `parseRoute` (or tolerate a trailing slash).

## Checked and fine
- Wire behaviour (zz-crit.spec.ts P1/P2, shipped inventory.spec.ts OD-7 test): only `/v2/...` paths are requested (plus the page and static assets); no unversioned call. Add and Purchase carry an Idempotency-Key; Create sends `PUT .../details` with `If-None-Match: *` and no key, then one keyed `POST /v2/inventory/{id}`; Edit sends `If-Match: "1"` (the ETag it read).
- FE9 key lifecycle: purchase forced through 429 -> 408 -> 400 -> success shows the same key three times, then a new key after the 400 (`19886e67 x3, 0cfcff25`); a 500 then click reuses the key. Enter pressed twice on Create sends one PUT and one POST; `dblclick` on a slow Purchase (1.5 s route delay) sends one request, form `aria-busy=true`, button `aria-disabled=true`.
- Create step 2 failure (503 on POST), back/forward/reload: the form state is lost as documented; a second Create answers 412 and links to the SKU (no second silent write, no duplicate stock).
- Two tabs editing the same SKU: the second Save gets 412 with a Reload button; If-Match values are the read ETags.
- After add then edit, the SKU page and Edit page show the server's current data (the Edit page re-reads; the add response replaces the SKU page's item); no stale value seen in the shipped create -> edit -> purchase flow.
- List: `limit=2` then Next page sends `GET /v2/inventory?limit=2&after=<last>` from the server's Link (re-rooted onto the app origin); list error via aborted request shows "Network error: Failed to fetch" with Retry; axe 0 violations on list, SKU, edit, new (both projects).
- States: 404 SKU, id with `/`, malformed percent-escape id, unknown route, add-on-404 creates the SKU, add of 2,147,483,647 twice gives 4,294,967,294 formatted; purchase on a missing SKU is unavailable with the reason.
- Rendering of server text: `<img src=x onerror=alert(1)>` in a name is rendered as text (React escaping, no `dangerouslySetInnerHTML` anywhere, no dialog fired); the image URL rule accepts only http/https, and `javascript:alert(1)` typed into the images box is blocked with "Line 1: use the URL as a browser shows it..." before sending. Images are `<img>` only (a `javascript:` URL would not execute in `<img src>` anyway); no `referrerPolicy`, so the default `strict-origin-when-cross-origin` applies (origin only leaks, the hash never does).
- Cost 2^53-1 in JPY renders `¥9,007,199,254,740,991`; the edit page says amounts above that can't be edited; RTL/Hebrew/Arabic text entered without breaking layout (s5/p6.png); focus rings (box-shadow on inputs, outline on buttons/links), reduced motion rule present (styles.css:309), 375 px and 640 px for the short-content pages have no horizontal scroll.
- Dependencies: `npm audit` 0 vulnerabilities; versions are all caret ranges in package.json with a lockfile; TS 5.9 / ESLint 9 pins explained in FE14. No dead code found by lint/tsc (`noUnused*` build clean). External request: Google Fonts link in index.html (documented FE25, falls back offline).
- Not tested: real 200% browser zoom (I resized to 640 px wide, which is the equivalent CSS width; the only overflow seen there was F-5-01), screen-reader output beyond roles/labels/aria-live inspected in code and axe, a stale-response race for the list under slow networks beyond the code's `n` sequence guard.
