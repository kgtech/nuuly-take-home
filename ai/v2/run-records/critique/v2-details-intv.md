# Critique — intv (interview defense) — v2 @ d4d2681 (reviewer report, condensed)

- F-intv-01 MAJOR — FABLE_REPORT.md describes the first run only (A1–A12, FE1–FE15, Redis as current, RedisFaultTest/StockCacheTest as invariant tests, "Redis replay copy" as top risk); PRs #74/#75/#77 and issues #71–#73/#76 absent. Contradicts DESIGN-V2 §9, A30, README.
- F-intv-02 MAJOR — interview-defense Q22 says local blocks show the server's texts (FE10); FE30 and the code say the opposite.
- F-intv-03 MINOR — DESIGN §8 names the If-None-Match omission as deliberate with no reason (the ETag tracks details, the body carries a quantity that changes on every purchase → a 304 would be stale).
- F-intv-04 MINOR — "a contract-valid body always fits the 32 KB cap" ignores `\uXXXX` escapes (a 2,000-char escaped description is 12 KB; ten escaped URLs ~123 KB).
- F-intv-05 MINOR — the fingerprint's length-prefixed form and the `create\n{skuId}\n` prefix are only in code/tests, not in DESIGN §8/A29.
- F-intv-06 MINOR — A26 (int64 minor units) does not record the JS 2^53 tradeoff the front end papers over (FE39, FE45).
- F-intv-07 MINOR — retry guidance "sending again is safe" is misleading for PUT with If-Match after a landed first attempt (412 + Reload discards edits); FE37's "replacement applied twice is the same state" assumes no If-Match.
- F-intv-08 MINOR — PUT on a malformed skuId is 400; DESIGN §8 gives no reason vs 404.
- F-intv-09 MINOR — A3–A9, A13, A15, A16 superseded by §9 but unmarked in DECISIONS-ADDED; interview Q4, Q11–12 likewise.
- F-intv-10 MINOR — `sku.version` kept with no reader; reason reads as YAGNI contradiction.
- F-intv-11 NIT — 409 text carries the literal `{skuId}`; the choice of a fixed text over interpolation is unrecorded.
- F-intv-12 NIT — root README omits that the dev proxy forwards `/v2`.

Weakest points named: the design document argues for a store that no longer exists; v2 surface with seams (F-03/04/06/07); the record inconsistent with itself (F-01/02/05/09).
