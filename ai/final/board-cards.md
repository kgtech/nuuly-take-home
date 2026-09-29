# Board cards for the final build

Every new or changed card, for the owner's approval at the plan gate. The board PR (issue F-01) enters them on final's board artifact and regenerates `DECISIONS.md` and `CLAUDE.md`. Nothing here is hand-edited into those files.

Legend: **Owner** = the owner's choice, cites the OD. **Rec** = my recommendation, needs an answer at the gate. Claims are marked **V** (verified by reading code or docs in the study or preflight) or **U** (unverified; the first test in Part 2 proves it, listed in the last section).

Old cards that change get a "refined by H-n" line; the edit list is in "Changed existing cards".

## New cards, H series

### H1: How are the spec and the extensions versioned?
- Options: (A) path prefix: the spec stays at `/inventory…`, everything else lives under `/v2`, a later incompatible change gets `/v3`, `/v2` only grows compatibly; (B) version in a header (Stripe, GitHub, Shopify style dated versions); (C) query parameter; (D) extend the unversioned API in place (build v2's shape).
- **Owner: A (OD-2).** Rejected: B, because a header is invisible in a URL, a curl line and the Swagger UI and needs a default for callers that send none; C, for the same reason and because caches key on the URL; D, because the spec's four operations would carry behavior the spec does not list (idempotency keys, `limit`, details).
- Trade-off accepted: two route sets over one stock, and two OpenAPI files. Version parity (invariant 6) is what keeps them honest.
- Rule: "[H1] The four spec operations live at /inventory and match the spec plus OD-5's page cap and the frozen hardening; nothing else. Everything the spec does not list lives under /v2/inventory; /v2 only grows compatibly, and an incompatible change gets /v3. Both versions read and write sku.quantity and the ledger. No unversioned request reads or writes the idempotency table. Test: version-parity walk, no-leak test, ArchUnit rule (L31)."
- Claims: Stripe, GitHub, Shopify and Google AIP-185 conventions are **U** (reviewer area 8 cites sources).

### H2: Are `/v2` stock writes keyed, and is the key required? (OD-3, OD-4)
- Options: (A) `Idempotency-Key` required on `/v2` add and purchase; (B) optional (build v2, and G8's choice A); (C) required on both versions.
- **Owner: A.** Rejected: B, because an optional key makes "retry-safe" depend on the caller remembering to send one, and the `/v2` contract is the place to say otherwise; C, because the spec has no key.
- Rule: "[H2] POST /v2/inventory/{skuId} and POST /v2/inventory/{skuId}/purchase require Idempotency-Key (S3's UUID pattern). Missing or malformed → 400 'Invalid request'. Order: body (@Valid) → key present and well formed → skuId (G11) → claim and write (A34). A key reused for another operation, skuId or request, or older than 24h, is 400 (T1, S8). A repeat with the same request replays the stored status, Content-Type and body (Y4)."
- Trade-off accepted: 400, not 409/422, for reuse and expiry, kept from build v2 (the IETF draft and Stripe differ; area 8 compares).

### H3: What does an unversioned POST do with an `Idempotency-Key`? (OD-4)
- Options: (A) reject any present header, including an empty one, with 400 and change nothing; (B) ignore it; (C) honour it (build v2, G8 choice A).
- **Owner: A.** Rejected: B, because a caller who sends a key believes the write is retry-safe, and it is not; C, because the spec never had the key.
- Rule: "[H3] POST /inventory/{skuId} and POST /inventory/{skuId}/purchase answer 400 'Invalid request' when Idempotency-Key is present with any value (an empty value and a repeated header count), and write nothing and store nothing. Order: body (@Valid) → key present → skuId → write. The unversioned controller and its service methods depend on no idempotency type."
- Test: HTTP rows for valid, empty, malformed and repeated header; zero rows in `sku`, ledger and `idempotency_keys`; ArchUnit rule (L31).

### H4: What is the unversioned list's page size? (OD-5)
- Options: (A) fixed 250, only `after`, Link carries only `after`; (B) keep `limit` (build v2); (C) unbounded (the spec).
- **Owner: A.** The recorded deviation from the spec on this path. Rejected: C, because an unbounded list failed with OutOfMemoryError at 1M SKUs (C-02); B, because page-size control is an extension and extensions live under `/v2`.
- Rule: "[H4] GET /inventory returns at most 250 items ordered by skuId (COLLATE 'C'). Only `after` is read: an exclusive cursor compared as a plain string, never validated. `limit` and every other parameter are ignored. When more rows exist the response has Link: <…/inventory?after=…>; rel='next', built from the routed path (C2), carrying only `after`. A query string that can't be decoded, or a repeated `after`, is 400 'Invalid request' (Z3). The deviation is stated in openapi.yaml's info.description and the README."

### H5: How does `/v2` list?
- Options: (A) `limit` 1–250 (default 250, lenient as R4) and `after`; Link to `/v2/inventory` carrying both; (B) strict `limit` (400 outside 1–250).
- **Owner: A** (Target API). Rejected: B, it would change build v2's behavior for no spec reason.
- Rule: "[H5] GET /v2/inventory: `limit` non-numeric or below 1 → 250, above 250 → 250; `after` as H4; Link: </v2/inventory?limit=…&after=…>; rel='next'; 400 as Z3. Every list response on either version holds at most 250 items (invariant 7)."

### H6: Where do the spec operations appear under `/v2`, and where do details go? (OD-6)
- Options: (A) `/v2` mirrors the spec operations at the same relative paths, and details are a sub-resource, `PUT /v2/inventory/{skuId}/details`; (B) keep build v2's create-with-details POST and PUT item (201/409).
- **Owner: A.** Removed: `POST /v2/inventory/{skuId}` create (201/409), `PUT /v2/inventory/{skuId}` and the 409 text. Rejected B: it mixes stock and details in one request, so a retry could double the stock or overwrite details.
- Rule: "[H6] /v2 routes: GET /v2/inventory, GET /v2/inventory/{skuId}, POST /v2/inventory/{skuId} (add), POST /v2/inventory/{skuId}/purchase, PUT /v2/inventory/{skuId}/details. Every /v2 body is a SkuItem (skuId, quantity, optional details). GET item carries ETag (the details version, "0" before any details) and Cache-Control: no-store; conditional-GET headers are ignored."

### H7: How does the details PUT create and replace? (OD-6, OD-11; replaces A23, A24, A28's 201/409)
- Options: (A) PUT creates a missing SKU at quantity 0 (201, ETag "1") and replaces an existing SKU's details (200, new ETag); `If-Match` (strong tags or `*`) and `If-None-Match: *` are optional preconditions; 412 when one fails; (B) 404 on a missing SKU (build v2); (C) 409 on an existing SKU.
- **Owner: A (OD-11).** Rejected: B, because creating then needs a second route and the create form could not be idempotent by method; C, 409 needs a create-only route.
- Rule: "[H7] PUT /v2/inventory/{skuId}/details replaces the whole SkuDetails (field rules: A26). It never changes stock and ignores Idempotency-Key (PUT is idempotent by method). SKU absent: 201 with ETag "1", quantity 0. SKU present: 200 with the new ETag. Without a precondition it is unconditional. 412 (H8) when: If-Match matches no current version; any If-Match, `*` included, on a SKU that does not exist; If-None-Match: * on an SKU that exists. `*` in If-Match means 'a current representation exists' (RFC 9110). An invalid skuId (G11, `;` content included) is 400 'Invalid request'. Order: body (@Valid) → skuId → preconditions → write."
- **Rec / open:** an unconditional PUT to an id nobody has created will create it (typo risk). That is what the Target API says; the front end sends `If-None-Match: *` on Create so it never overwrites. Interview question, see area 7.
- **Rec:** `If-None-Match` with any value other than `*` → 400 'Invalid request' (silently ignoring a precondition can overwrite data). **U:** what build v2's `IfMatch.parse` does with malformed values today; the test decides.
- Claims **U:** Spring does not answer PUT conditional headers by itself (first test); concurrent PUT-create vs add on a new SKU never loses either (existing test `aCreateAndTheSpecAddOnOneNewIdBothLand` covers create vs add; new test for PUT-create).

### H8: The 412 text (changes G6)
- Options: (A) one fixed text, "Details changed since you read them. Reload the SKU and retry with its new ETag.", for every 412; (B) a second text for `If-None-Match` on an existing SKU.
- **Owner: A** (Target API fixes the text). Drop the 409 text ("SKU already exists…"). The text reads oddly on the Create page (nobody read anything); the front end shows the server text and adds its own line 2.
- Rule: "[H8] G6's four texts plus, on PUT /v2/inventory/{skuId}/details only, 412 with the fixed text above."

### H9: V4 policy for `'create'` and 201/409 in the idempotency CHECKs
- Context: V3 (published on `v2`, never editable) allows operation `'create'` and statuses 201/409. Nothing writes them after OD-6. Rows are never purged (R9). V: V3 lines 20-28, study A section 3.
- Options: (A) V4 narrows both CHECKs with `NOT VALID`; (B) no DDL: remove `Operation.CREATE` in Java, leave the CHECKs wide, test that nothing writes them; (C) V4 narrows with validation.
- **Rec: A.** The schema then says what the API can store, it cannot fail on any database, and old rows stay readable (a leftover `'create'` key on `/v2` add fails as a different operation → 400, by design). Cost: `convalidated = false` until someone runs VALIDATE. C fails on a database with an old row; B leaves the schema lying.
- Rule: "[H9] The idempotency table allows operation add|purchase and status 200|400|404. V4 narrows the V3 CHECKs with NOT VALID, so rows written by earlier builds stay readable. Nothing writes create, 201 or 409."
- **U:** `NOT VALID` semantics on Postgres 18 (a test inserts a `'create'` row into a table with the old CHECK, applies V4, then asserts new inserts fail and the old row survives).

### H10: Does the request hash include the API version?
- Options: (A) yes: hash input `"v2" + "\n" + operation + "\n" + skuId + "\n" + quantity`; (B) no (Y3's bytes).
- **Rec: A.** A key stored by build v2's unversioned POST hashes the same inputs and holds an `InventoryItem` body; without the version it would replay that body on `/v2`. With it, such a key gets 400. No DDL. Changes Y3: "same bytes as before, so stored keys still replay" becomes false on purpose; RequestHashTest changes.
- Rule: "[H10] request_hash = SHA-256 of 'v2' + '\n' + operation + '\n' + skuId + '\n' + canonical request, computed from the parsed request (Y3). A key stored by an unversioned request never matches a /v2 request."

### H11: The guard filter is driven by route kind (lesson L21, freezes A19, C3 wording)
- Options: (A) classify each request by route kind (LIST, ITEM, PURCHASE, DETAILS) and apply every frozen check to every write and read route of either version; (B) keep segment counts and add cases.
- **Rec: A.** Freeze the rules, not the route list: `;` in the skuId segment (write item → 400, write purchase → 404, GET → 404, PUT details → 400), Accept refusal on POST and PUT, body cap by route kind, all on the routed path. Body cap by kind: 4 KB for both POSTs on both versions, 64 KB for PUT details (counted while read when chunked). Unversioned chunked bodies stay uncapped as in build v2 (recorded, not changed: A19). The filter still runs before `@Valid` (M-13): a `;` skuId with a bad body on purchase gets the skuId answer, 404. Tie-break for equally specific Accept ranges stays "first listed" (C3's card changes to match).
- Rule text replaces C3's mechanism and A19; see edits below. Test: a route-enumeration test lists every mapped route with `{skuId}` and asserts the frozen answer plus zero rows written; a new route the filter doesn't know fails it.
- Claim **U:** the L21 repro on the tip (`POST /v2/…;x/purchase`) and the tie-break example `application/json;q=0, application/json;q=1` (frozen: 400).

### H12: OpenAPI is two documents, checked against the spec
- Options: (A) two springdoc groups, `openapi.yaml` (1.0.0) and `openapi-v2.yaml` (2.0.0), both exported and committed byte-stable, plus a conformance test against the spec's YAML; (B) one document (build v2).
- **Owner: A** (Target API). Rule: "[H12] openapi.yaml documents exactly the spec's four operations (spec summaries, descriptions and examples, plain `type: string` skuId). A conformance test parses the YAML block of docs/NUULY-ASSESSMENT-README-JUL-2026.md, normalizes both documents (path-level parameters pushed down, Error $ref inlined), and allows only these differences, each with its decision ID: the openapi version string; generated servers and tags; info.description (states the deviations, points to /v2; H4); on GET /inventory, the operation description and the 200 description (250 cap, `after` cursor), the `after` parameter, the Link header, the 400; integer formats if springdoc can't omit them. openapi-v2.yaml carries C2's skuId schema (pattern, minLength, maxLength), the Idempotency-Key header (required) and the 201 and 412 responses. Front-end types are generated from openapi-v2.yaml only (FE5, FE23)."
- Claims **U:** springdoc 3.1.x group `info` and class-level `@OpenAPIDefinition` interplay; what `/v3/api-docs` serves once groups exist; byte stability of each group's YAML; Swagger UI lists both groups.

### H13: Ports (replaces A20; S4's wording)
- **Owner instruction (mid-run): use the ports main uses.** Options: (A) app on host 8080, Vite on 5173 (main's; container port 8080); (B) 18080 and 15173 (build v2).
- Rule: "[H13] compose.override.yaml publishes the app on host port 8080 (APP_PORT overrides); the Vite dev server uses 5173 and proxies /v2 to 8080; Playwright uses the same defaults. ComposeFilesTest asserts it." **Note:** 8080 was free in preflight; if it is taken on a reviewer's machine, `APP_PORT` overrides.

### H14: Build order and budget (replaces T6)
- **Owner: prompt.** 5 hours from approval; critique starts by 3 h 15 min; the last 30 minutes are for the report; the PR order is in `ai/final/plan.md`. Rule: "[H14] The build follows ai/final/plan.md. When the budget ends, stop and push what is green. Anything not built goes under 'Designed, not built' in the README with its decision ID."

### H15: Architecture guards (rewrites A39)
- **Rec:** add ArchUnit (`com.tngtech.archunit:archunit`, test scope, version only in `gradle/libs.versions.toml`, S10; owner approved earlier) and write only rules that hold on the real layout: unversioned controller and its service methods depend on no idempotency type (L31); no `getRequestURI` outside one `RoutedPath` helper plus an allow-list (L19); every `@SpringBootTest` is `@IntegrationTest` and only `TestcontainersConfiguration` declares a container (L27); domain and idempotency packages import no web types (kept in `PackageBoundaryTest`, which stays); no JPA or Spring Data on the classpath (E2). Rules that the code violates today (domain annotations) are relaxed to the wire types.

### H-M13: The guard filter runs before body validation
- Frozen from build v2 (open item M-13, `ai/v2/REPORT.md` lines 72 and 96). Rule: "[H-M13] Because the guard filter runs before argument resolution, a request that has both a `;` skuId and a bad body gets the skuId answer (400 for add and PUT details, 404 for GET and purchase), not the body's 400. U3 and G4's 'a bad body wins' hold everywhere the filter does not decide." Revisited only if the critique rates it MAJOR or worse.

## Build v2 cards with no board card (promoted from `ai/v2/DECISIONS-ADDED.md`)

| ID | Question | Choice | Rule text (short) |
|---|---|---|---|
| A17 | Key format; replay header? | UUID; no `Idempotent-Replayed` header | "The Idempotency-Key is a UUID wherever it is read (S3); OpenAPI says format uuid; no replay header." (refined by H2, H3) |
| A19 | Write body size | 4 KB by Content-Length on both POSTs; 64 KB on PUT details; over the cap → 400 before parsing | see H11 |
| A22 | Where do details live? | own table `sku_details` (PK sku_id, version) | "Details are rows in sku_details, never columns on sku; the stock UPDATE never touches them; a details PUT never updates sku.quantity or sku.version. A PUT that creates a SKU inserts the sku row (quantity 0) and the details row in one transaction." |
| A25 | `/v2` error format | text/plain fixed texts through the S5 helper; RFC 9457 rejected | "[A25] /v2 errors are text/plain built by the S5 helper with G6's texts plus H8's 412." |
| A26 | Cost and SkuDetails field rules | `{amount int64 minor units, currency ^[A-Z]{3}$}` both or neither; name 1–120 not blank; description ≤ 2000; ≤ 10 http/https URLs ≤ 2048 each; decimals and floats refused | "[A26] as stated; an unknown property is ignored (G13)." |
| A27 (rewritten, Redis half dropped) | How is a `/v2` read served? | one `sku LEFT JOIN sku_details` query, no transaction; `ETag` and `Cache-Control: no-store`; conditional GET headers ignored (never 304) | "[A27] …" **U:** `no-store` on the current GET |
| A30 (folds A31, A32) | Any store besides Postgres? | Postgres only: no Redis, no cache, no post-commit writes | "No performance claim without a README benchmark." A31/A32 → "Designed, not built" |
| A35 | Store a domain outcome instead of the HTTP response? | deferred (#83) | "[A35] the idempotency row stores the rendered response (Y4)…" |
| A36 | Message broker? | none (#85) | "[A36] no broker; a later one is a new API version and uses a transactional outbox." |
| A20 | (superseded by H13) | | |
| A21, A23, A24, A28 | superseded by H1, H6, H7, H9 | | Recorded on those cards as "replaces A2x". |

## Changed existing cards

Full old text, new text and kind (edit, both, new) for each are in `ai/final/current-state.md`, Part B, table A and table B. Summary:

| Cards | Change |
|---|---|
| G8, S3, U3, A34, A33 | Key required on `/v2`, rejected on unversioned. Two check orders. The service has separate unkeyed (unversioned) and keyed (`/v2`) entry points. |
| G9, R4, R8, C2, Z3 | `limit` moves to `/v2` (H5). Unversioned: fixed 250, `after` only, Link without `limit` (H4). The skuId schema documentation moves to `openapi-v2.yaml`. Repeated-`after` 400 stays on both. |
| G10, G6, G3, R3, G11, G13, Y1, U2 | `/v2/inventory/**` named alongside `/inventory/**`; PUT details named beside the two POSTs; G6 gains the 412 text and loses the 409 text. |
| G14, R1, R2, S8, T1, R9, Y3, Y4, A18 | Scoped to `/v2`; Y3 adds the version (H10); Y4's replay body is a SkuItem. |
| C3 | Mechanism is the guard filter (not an interceptor, not a controller-passed raw segment); tie-break "first listed"; scope covers `/v2` purchase and details. |
| S12, D7, S11, D5, E1, E2, E3, S1, S4, A37, A38, A39, Z2, D10, C1, C2, S10, T6, G5, A27 | Card text changed to match the code (see Part B of `current-state.md`), or a refactor. Recommended refactors, each small or deleting code: shared `Paging` helper (S, in the unversioned-list PR); typed `WriteResult<O>` and one `Page<T>` after OD-6 removes `create` (S–M, in the removal PR); `OpenApiConfiguration` (S, in the OpenAPI PR); ArchUnit (M, H15). Not recommended: moving the four web classes into an app-wide `web/` package (M, no user), rewriting purchase into one statement (E1: change the card), a `MigrationUpgradeTest` (E3: change the card). |

## Claims to prove first (U items, in the order Part 2 tests them)
1. Spring does not answer PUT `If-Match` / `If-None-Match` itself (PUT 201 with `If-None-Match: *`; PUT 200 with a non-matching `If-None-Match`).
2. L21 repro: `POST /v2/inventory/A;x/purchase` reaches another SKU on the tip; `PUT …;x/details` after the route exists.
3. springdoc: two `GroupedOpenApi` beans, per-group `info`, `@OpenAPIDefinition` removal, byte-stable YAML, Swagger UI lists both, what `/v3/api-docs` serves.
4. The spec's YAML block parses and normalizes as the conformance test expects (path-level `parameters`, `Error` `$ref`).
5. Postgres `ADD CONSTRAINT … NOT VALID` on the V3 checks with an existing `'create'` row (H9).
6. `IfMatch.parse` behavior for malformed values, and `If-Match: *` must stop meaning "unconditional".
7. The tie-break example under the frozen filter (`application/json;q=0, application/json;q=1` → 400).
8. `Cache-Control: no-store` on GET item; no 304 (A27).
9. The unversioned chunked body is uncapped (A19, measured in build v2 as 40 MB in 0.65 s).
10. Node LTS for CI (Node 22 or 24) runs the front-end gate; `check:api` passes against `openapi-v2.yaml`.
11. Mixed-version concurrency: purchases through both versions never oversell; PUT-create racing an add on one new SKU loses neither.
