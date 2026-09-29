# Critique — fe (front end) — v2 @ d4d2681 (reviewer report, condensed)

- F-fe-01 MAJOR — the client's image-URL host class `[A-Za-z0-9.-]+` blocks IPv6-literal hosts (`http://[::1]/a.png`) that java.net.URI accepts; the MSW mock copies the regex so no RTL test catches it; a SKU stored with such a URL makes the edit page's Save permanently unavailable.
- F-fe-02 MINOR — `parseImages` uses JS `trim()` (strips NBSP/U+FEFF), so the request can differ from the visible input, contrary to FE43.
- F-fe-03 MINOR — `type="number"` fields report `''` for unparsable text (`12-3`, `1e`); optional numeric fields (initial stock, cost amount) then send 0 / omit cost without a hint.
- F-fe-04 MINOR — the edit page sends an unconditional PUT when the read had no ETag (proxy stripping it); FE37 says the header is always sent. Refuse to save or show a reason.
- F-fe-05 MINOR — after a successful add on a non-404 load error (502/network), the SKU page shows "No details yet" for a SKU that may have details; merge only when the previous state was ready, else re-fetch.
- F-fe-06 MINOR — MSW PUT checks existence before body validity and treats an empty If-Match as stale (412) where the service answers 400; the 412 unit test bumps the store directly.
- F-fe-07 MINOR — no Playwright evidence of FE9 on `POST /v2` (double click / retry with one key).
- F-fe-08 NIT — raw big quantity on the SKU page; `maxLength={3}` on Currency; 11px Reload label; edit page shows the literal "SKU not found" for a G11-invalid URL id (FE21 precedent, undocumented for the edit page).

Checked and found met: one POST per create with FE9 lifecycle; 201/409 handling; edit with If-Match and 412 Reload; v2 reads; field-specific local reasons; validation parity with the server except IPv6; aria-disabled pattern; single outcome area with stale guard and Purchase soft-block; guidance lines; Request reference; list cursor state and hint; generated types only; MSW ETag/409/412/replay; Playwright flows and axe/375; focus and keyboard; stylesheet rules.
