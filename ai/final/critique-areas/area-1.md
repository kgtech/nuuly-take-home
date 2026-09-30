# Area 1: spec and versioning (SHA 436cacae)

Method: live stack (compose project crit1, port 18201), about 300 real requests on both versions, plus reading openapi.yaml, openapi-v2.yaml, the spec and README. The DB was inspected with psql. No tracked file was changed.

### F-1-01 [MAJOR] /v2 ETag is a strong validator that does not change when the representation changes
- Location: GET /v2/inventory/{skuId} (and the PUT details responses); README "API versions" table, openapi-v2.yaml getSku ETag header
- What is wrong: the ETag is only the details version ("0" before details), but the body also carries `quantity`. Under RFC 9110 a strong ETag must change when the representation changes. Here a purchase or add changes the body and leaves the ETag as it was. Scenario: a client reads `ETag: "1"` with quantity 3; someone adds 10; the next GET has `ETag: "1"` and quantity 13. It is harmless today only because Cache-Control is no-store and conditional-GET headers are ignored. It is also a compatibility trap for "/v2 only grows compatibly": the day anyone honours `If-None-Match` on GET (304), every client and proxy holding the old tag is served stale stock. That would be a breaking change, or would need /v3. It also makes the "If-Match protects the details" semantics an accident of the tag being reused for a different purpose than the resource's validator.
- Evidence: `curl -D- localhost:18201/v2/inventory/V-1` gave `ETag: "1"` and `"quantity":3`. After `POST /v2/inventory/V-1` with quantity 10, the same GET gave `ETag: "1"` and `"quantity":13`. `GET` with `If-None-Match: "0"` returned 200 (ignored, as documented).
- Suggested fix: either name the tag as details-only (a separate `Details-Version` style header or a weak tag) or make the ETag cover quantity too. Decide it before /v2 has real clients.

### F-1-02 [MINOR] Extra header `Content-Disposition: inline;filename=f.txt` leaks onto unversioned (and /v2) responses for dotted SKU ids
- Location: any route whose skuId ends in `.<ext>` (for example `/inventory/A.b`, `/inventory/Z.zip`), both versions, 200 and 404 alike
- What is wrong: Spring's reflected-file-download guard adds `Content-Disposition` when the request path ends in a (non-whitelisted) extension. The unversioned API is supposed to be the spec plus the frozen hardening, and nothing more. The header appears only for some valid SKU ids (`A.b`, `A.xml` 404), not for others (`A.json`, which is whitelisted). This is undocumented and looks arbitrary. It is harmless for JSON clients but is a byte-level difference on a spec path.
- Evidence: `curl -i localhost:18201/inventory/A.b` gives `HTTP/1.1 200`, `Content-Disposition: inline;filename=f.txt`, `Content-Type: application/json`, body `{"skuId":"A.b","quantity":1}`. `POST /inventory/A.b` and `POST /inventory/A.b/purchase` behave the same. `/inventory/A.json` has no such header.
- Suggested fix: turn off suffix handling or the RFD header for the API paths (a filter that strips the header, or an explicit content negotiation config), and add a header-set test on a dotted SKU.

### F-1-03 [MINOR] A path Tomcat rejects gives 400 where G11 says an invalid GET skuId is 404; spec GET lists no 400
- Location: `GET /inventory/{skuId}` with `%00`, `%5C`, bad percent-escape or invalid UTF-8 in the path (C1)
- What is wrong: every other invalid skuId on GET answers 404 "SKU not found" (`-x`, `a%20b`, `%C3%A9`, `A%2FB`, 65 chars, `;`). But `/inventory/A%00B` and `/inventory/A%5CB` answer 400 "Invalid request", and `/inventory/%zz` too. The spec's GET /inventory/{skuId} has no 400. A client cannot tell "invalid id, 404" from "invalid id, 400" by the rule "an invalid skuId is 404 on GET". It is accepted hardening (C1) but it contradicts the sentence "an invalid skuId answers 404 on the GET" and openapi.yaml does not document the 400.
- Evidence: `curl --path-as-is -i localhost:18201/inventory/A%5CB` gives 400 text/plain;charset=UTF-8 "Invalid request". `.../inventory/%C3%A9` gives 404 "SKU not found". The same for `A%00B`.
- Suggested fix: state it in the README Assumptions (skuId with %00/%5C/malformed escapes is 400 on every operation) or map these to the skuId rule; at least say it in openapi.yaml's info.description.

### F-1-04 [MINOR] Error Content-Type varies: `text/plain` vs `text/plain;charset=UTF-8`, and Content-Length vs chunked
- Location: unversioned and /v2 errors
- What is wrong: the same status has two Content-Type strings depending on which layer produced it. Controller and advice errors are `text/plain` with Content-Length. Interceptor, guard-filter and Tomcat-valve errors are `text/plain;charset=UTF-8`, chunked. Examples: POST with `Accept: application/xml`, the body cap, `;` in the skuId (also the 404 for GET/purchase there), bad percent-escape. A strict client or a byte-for-byte test comparing headers sees two forms. The spec says only `text/plain`.
- Evidence: POST `/inventory/ABC-1` with `Accept: application/xml` gives `400`, `Content-Type: text/plain;charset=UTF-8`, `Transfer-Encoding: chunked`. POST with body `{}` gives `400`, `Content-Type: text/plain`, `Content-Length: 15`. `GET /inventory/ABC-1;lot=7` gives 404 `text/plain;charset=UTF-8`, chunked, while `GET /inventory/NOPE` gives `text/plain`, Content-Length 13.
- Suggested fix: pick one form in the shared TextErrors writer and set it plus the length everywhere.

### F-1-05 [MINOR] /v2 responses carry an undocumented `Cache-Control: no-store` on the list, and the OpenAPI omits it everywhere
- Location: GET /v2/inventory (list); PUT details 200/201; GET item; openapi-v2.yaml
- What is wrong: the README and PROMPT mention `Cache-Control: no-store` only for GET one (and the spec brief says nothing for the list or PUT). It is in fact sent on the list and on PUT responses too, and the OpenAPI documents an ETag header but no Cache-Control header on any response. Unversioned paths correctly have none (checked). It is a compatible extension, but it is undocumented, so a later change to caching is unclear to clients.
- Evidence: `curl -sD- -o /dev/null localhost:18201/v2/inventory` shows `Cache-Control: no-store`. openapi-v2.yaml has no `Cache-Control` header anywhere (grep).
- Suggested fix: document the header on all /v2 200/201 responses, or drop it from the list.

### F-1-06 [MINOR] README claim "Unversioned chunked bodies are uncapped" is imprecise
- Location: README "Deviations", known divergences
- What is wrong: an unversioned chunked POST is not fully uncapped. A 150 MB chunked body with one huge string value answers 400 in under a second (a Jackson string length limit, not the A19 cap), while a 5 KB chunked body is accepted (200). So the behaviour is "no A19 cap; Jackson's limits apply", and a reviewer who tries a large body and sees 400 will not see what the README says. A whitespace or array-heavy body (not tried) probably streams without a cap.
- Evidence: `curl -X POST localhost:18201/inventory/BIG-1 -H 'Transfer-Encoding: chunked' --data-binary @150MB.json` gives 400; the same 5 KB body chunked gives 200 on unversioned and 400 on /v2.
- Suggested fix: reword to "capped only by Jackson's default limits".

### F-1-07 [DECISION CHALLENGE] Lenient `limit` on /v2 (bad or repeated value ignored) cannot be tightened without a /v3
- Location: GET /v2/inventory (R4, C2); openapi-v2.yaml
- What is wrong: `limit=0`, `limit=abc`, `limit=-1`, `limit=1.5`, `limit=2147483648`, an empty `limit`, and `limit=2&limit=3` all give 200 with 250 items, and 999 means 250. By OD-2 "/v2 only grows compatibly", so once a client relies on it, making a bad `limit` 400 later (the usual choice for a new versioned API) is a breaking change. The same applies to an unconditional PUT details creating a SKU (no precondition required; 428 later would break). These are choices made for the unversioned path (R4) and copied to the versioned one. Interview question: "why is the new API as forgiving as the old one, and what does that leave for /v3?"
- Evidence: the loop of `limit=` values above all gave `200`, `limit=250` in the Link.
- Suggested fix: none needed now; record it in DECISIONS as a known /v2 lock-in.

### F-1-08 [DECISION CHALLENGE] Unversioned GET /inventory answers 400 for any undecodable query, though the spec lists no 400
- Location: `GET /inventory?x=%zz` (also `?after=a&after=b`)
- What is wrong: a spec client that appends an unrelated tracking parameter with a bad escape gets 400 on a route the spec says only returns 200, while `GET /inventory/{skuId}?x=%zz` and POSTs ignore the query and answer normally (200). The rule "400 only where the query is read" is documented and is in openapi.yaml, but it is a divergence beyond OD-5's `after` cursor for something the spec client never asked for. The unrelated-parameter 400 is what to question.
- Evidence: `GET /inventory?x=%zz` gives 400 "Invalid request"; `GET /inventory/Q-1?x=%zz` gives 200.
- Suggested fix: ignore undecodable parameters other than `after`, or accept as documented.

### F-1-09 [NIT] Every 400 closes the connection (`Connection: close`)
- Location: all 400 responses, including the spec's "Insufficient inventory" 400 on purchase
- What is wrong: Tomcat drops keep-alive after a 400. Spec-mandated business 400s (insufficient inventory, quantity 0) therefore cost a new TCP connection each. It is inherent to Tomcat and to the spec's 400 use, but no note says so.
- Evidence: every 400 above shows `Connection: close`; 200/404 do not.
- Suggested fix: none; optionally note it under Assumptions.

### F-1-10 [NIT] Small doc and header oddities
- README "API docs" repeats `/v3/api-docs.yaml/{group}` twice in one sentence. The 405 `Allow` lists omit OPTIONS while the OPTIONS response lists it (Spring default). `OPTIONS` answers a bare `Accept-Patch:` header with an empty value on all paths (Spring default; unversioned path has an extra empty header).
- Evidence: `curl -i -X OPTIONS localhost:18201/inventory` shows `Accept-Patch:` with an empty value.

## Checked and fine
- Unversioned 200/400/404 on all four operations, byte level: bodies, Content-Type, no extra fields (only `skuId`, `quantity`), error texts exactly the four G6 texts; no ETag, Link (except list), Cache-Control, Vary or other extra headers, except F-1-02.
- Case-sensitive skuId: `abc-1` and `ABC-1` are separate SKUs on both versions; GET with the wrong case is 404.
- Invalid skuIds (`-x`, `.x`, space, `%2F`, `;`, `%3B`, 65 chars, non-ASCII, trailing slash): GET/purchase 404, create 400, on both versions; the 64-char id works; nothing written for invalid ids.
- Bodies: empty, `{}`, string/float/null/0/negative/over-int quantity, trailing tokens, non-JSON, array, missing/wrong Content-Type: all 400 on both versions, with the same status and Content-Type on both. Unknown fields ignored. Content-Type with charset accepted. 5 KB body capped on both; chunked cap differs (documented).
- Accept: GET ignores it on both versions; POST with xml/html/text/plain/q=0 gives 400; `*/*` and json;q=0.1 give 200.
- Methods: PUT/DELETE/PATCH/TRACE give 405 text/plain with Allow; HEAD works; OPTIONS 200; unknown paths (`/v2`, `/v2/`, `/inventory/`, `/inventory/x/y`, `/V2/...`, `/v3/...`) 404 text/plain "Not Found"; `/%76%32/inventory` routes to /v2 as expected.
- Idempotency-Key on unversioned: valid UUID, empty value (`Idempotency-Key;`), lowercase header name, on either POST, with bad body, and with a bad skuId all give 400 and change nothing; GET and list ignore it. After all this, `idempotency_keys` held only /v2 rows (psql), so no leak.
- /v2 keys: required (400 missing, empty, malformed), replay returns the same 200, a different quantity or the same key on another operation gives 400, uppercase UUID accepted, duplicate header gives 400; 404 and 400 outcomes stored as documented.
- /v2 details PUT: 201/`ETag "1"` create, 412 for `If-None-Match: *` on an existing SKU, `If-Match` match/stale/weak/`*`/missing SKU, other `If-None-Match` value gives 400, unconditional create 201, stock unchanged by PUT, key ignored, `;` skuId 400, bad skuId 400, missing body and `{}` 400. A PUT-created SKU shows at quantity 0 on the unversioned side.
- Unversioned list: 250 cap, `limit` ignored, `after` cursor with `Link` carrying only `after`, repeated `after` 400, `after=` empty, `after=%00` (first page), Link host follows the Host header only (X-Forwarded-* ignored, as documented).
- Version parity: with 310 SKUs, the walk of `/inventory`, `/v2/inventory` and `/v2/inventory?limit=7` gave identical (skuId, quantity) lists in the same COLLATE "C" order, with no duplicates. A purchase through one version is visible through the other.
- `/v2` twin routes vs openapi-v2.yaml: every documented status observed (200/201/400/404/412); GET item ETag/`no-store` as documented. openapi.yaml matches the spec block apart from the allowed and documented differences (SpecConformanceTest table read; every allowed difference has a decision ID).
- Springdoc groups served at `/v3/api-docs.yaml/inventory`, `/v3/api-docs/inventory` and `/v3/api-docs.yaml` (all 200).
- README versioning claims (how to reach every SKU through each version, Link shapes, key rules, 412 text) match observed behaviour.
- Not run: the Gradle test suite, Playwright, and a whitespace/array-heavy chunked body.
