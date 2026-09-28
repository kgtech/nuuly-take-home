# Critique — spec (spec and contract) — v2 @ d4d2681 (reviewer report, condensed)

BLOCKER/MAJOR: none.
- F-spec-01 MINOR — Jackson stringifies JSON numbers/booleans into the v2 string fields (`{"name":123}` → 201 "123"); `allow-coercion-of-scalars` covers only numeric/boolean targets.
- F-spec-02 MINOR — only C0 controls are rejected; C1 (U+0080–U+009F, e.g. NEL) pass the validator and may fail the DB blank CHECK → 500.
- F-spec-03 MINOR — the streaming cap is v2-only; a chunked v1 POST has no size limit (A19 accepted this; document the asymmetry).
- F-spec-04 NIT — the 201's ETag description says "0 before any details"; a 201 always carries "1".
- F-spec-05 NIT — `replace()` binds If-Match twice (an unused documented parameter and the raw headers).
- F-spec-06 NIT — lengths are UTF-16 units in code, "characters" in the OpenAPI text.
- F-spec-07 NIT — openapi-typescript makes `initialQuantity` required because of `default: 0`.
- F-spec-08 NIT — a replayed keyed 201 carries ETag "1" even after a later PUT; unstated in §8.
- F-spec-09 NIT — no same-key concurrent v2 create test (covered by the conc fix round).

Checked and found met: v1 byte-for-byte; v2 codes/media types/ETag/If-Match/Link; G6/S5/T3; G11 per operation; G13; guard and Accept filter on /v2; the cap; the v2 Link; regenerated export and FE types; README walk-through; #71 AC1–AC10 and #76 mapped to tests; PUT and create lock invariants.
