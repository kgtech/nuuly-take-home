# Area 3: API contract and errors (SHA 436cacae24a4dc4db80a03f520843b1ca3d9bbb9)

Stack: `APP_PORT=18203 docker compose -p crit3 up --build -d` (torn down, worktree removed).
Tools: hand-written curl and raw-socket (python) probes in /private/tmp/claude-501/critique/s3; Schemathesis 4.28.0 (pip, in a scratch venv, nothing added to the repo) run against openapi.yaml (max-examples 150, all checks, stateful) and openapi-v2.yaml (max-examples 25 after a 150 run was too slow).
I did not read any of the forbidden files.

### F-3-01 [MAJOR] GET/POST/PUT/... /error is a client-triggerable 500 with a JSON (or HTML Whitelabel) body
- Location: any request to the literal path `/error` (Spring Boot's BasicErrorController mapping is exposed; InventoryErrorAdvice's S6 rules say /error is NOT a library path).
- What is wrong: CLAUDE.md G10/S6/T3 say unknown paths under the standard-code rule get 404 text/plain "Not Found", and 500 is "for server faults, body 'Internal server error'". A plain `GET /error` gives 500 with Boot's JSON body `status 999`, and with `Accept: text/html` a "Whitelabel Error Page" (HTML). Every method except OPTIONS does this. Any client (or scanner) can make the server return 5xx and HTML/JSON. `/error/`, `/error/x`, `/Error` correctly give 404 text/plain, so this is only the exact path.
- Evidence:
  - `curl -si localhost:18203/error` -> `HTTP/1.1 500`, `Content-Type: application/json`, `{"timestamp":"...","status":999,"error":"None"}`
  - `curl -si -H 'Accept: text/html' localhost:18203/error` -> 500 `text/html;charset=UTF-8` `<html><body><h1>Whitelabel Error Page</h1>...`
  - Same for `POST/PUT/DELETE/PATCH/TRACE /error` (500 JSON), `HEAD /error` 500, `GET /error?a=1` 500.
- Suggested fix: turn off Boot's error controller mapping for direct requests (e.g. `server.error.path` to an unroutable path plus `spring.web.resources.add-mappings=false`/exclude BasicErrorController), or have the advice/filter answer a direct hit on `/error` (no forwarded error attributes) with 404 text/plain "Not Found".

### F-3-02 [MINOR] Malformed chunked framing on a write route gets Boot's JSON 400 instead of text/plain
- Location: POST /inventory/{sku}, POST /inventory/{sku}/purchase, POST /v2/inventory/{sku}(/purchase), PUT /v2/inventory/{sku}/details (body read fails inside Tomcat).
- What is wrong: the requirement "errors are text/plain everywhere" (G3, S5, C1) is broken when the chunk-size line is invalid. The status (400) is right, but the body is `application/json` from Boot's /error. Other body failures (short chunk, CL+TE, truncated JSON) are correctly text/plain "Invalid request".
- Evidence (raw socket): `POST /inventory/A1 HTTP/1.1` with `Transfer-Encoding: chunked` and body `zz\r\n{}\r\n0\r\n\r\n` ->
  `HTTP/1.1 400`, `Content-Type: application/json`, `{"timestamp":"2026-09-29T21:23:52.367Z","status":400,"error":"Bad Request","path":"/inventory/A1"}`. Same on `/v2/inventory/A1`, `/inventory/A1/purchase`, `/v2/inventory/A1/details`. (Sending a chunk-size larger than the bytes actually sent gave the same JSON.)
- Suggested fix: make the TextErrorReportValve / advice also cover the ErrorPage dispatch for these (map the IOException/ClientAbort body-read failure to TextErrors.invalidRequest), or register Boot's `/error` view as text/plain via the same helper.

### F-3-03 [MINOR] Integer-valued JSON floats (5.0, 1e2) are rejected although the published OpenAPI is 3.1 (where 5.0 is a valid integer)
- Location: `quantity` on all four POSTs; openapi.yaml / openapi-v2.yaml declare `openapi: 3.1.0` and `type: integer`.
- What is wrong: G13 deliberately rejects float->int coercion, but the published contract (3.1 = JSON Schema 2020-12) says `13286.0` is a valid integer. Schemathesis reports "API rejected schema-compliant request" for both unversioned POSTs. A consumer generating requests from the OpenAPI file can be rejected on a document-valid body.
- Evidence: `curl -X POST -H 'Content-Type: application/json' -d '{"quantity": 13286.0}' localhost:18203/inventory/CW-XYCS-BM-01` -> `400 Invalid request`; also `{"quantity":1e0}`, `1E2`, `5.0` all 400. Schemathesis: "API rejected schema-compliant request ... Reproduce with: curl -X POST ... -d '{\"R\": [181835983], ..., \"quantity\": 13286.0}'".
- Suggested fix: either state the rule in the OpenAPI (`openapi: 3.0.3` where integer excludes 5.0, or a `pattern`/description "written without a fraction or exponent") or accept integral floats.

### F-3-04 [MINOR] Duplicate JSON keys are accepted (last one wins) on the money-moving and details bodies
- Location: request deserialization (Jackson STRICT_DUPLICATE_DETECTION is off; application.yaml has no `spring.jackson.parser.strict-duplicate-detection`).
- What is wrong: `{"quantity":1,"quantity":2}` is ambiguous JSON; different parsers (a proxy, a client, the request-hash inputs) can disagree on it. The server silently applies quantity 2. G13 lists strict coercions but this is the same class of ambiguity. The idempotency hash is computed from the parsed value so a replay is consistent, but the client's intent is unknowable.
- Evidence: `curl -X POST -H 'Content-Type: application/json' -d '{"quantity":1,"quantity":2}' localhost:18203/inventory/S1` (S1 had 1) -> `200 {"skuId":"S1","quantity":3}`. `PUT /v2/inventory/D1/details` with `{"name":"ok","name":"dup"}` -> 200, details.name = "dup".
- Suggested fix: `spring.jackson.parser.strict-duplicate-detection: true` (Jackson 3: `JsonReadFeature`/`StreamReadFeature.STRICT_DUPLICATE_DETECTION`), answer 400.

### F-3-05 [MINOR] Unversioned writes: chunked body is not size-capped (frozen A19, but verify the risk is accepted)
- Location: InventoryRequestGuardFilter.doFilterInternal ("Frozen, not changed (A19): an unversioned chunked body is not counted while read").
- What is wrong: the 4096-byte cap is only on Content-Length. A chunked request is read to the end: a 100 MB chunked body of ignored properties returned 200 and applied the stock change (a 25 MB single string is stopped by Jackson's string-length limit with 400). CPU/thread time is spent for the whole stream; /v2 has the CappedBodyRequest counter but the spec routes do not.
- Evidence: raw socket, `POST /inventory/BIG1` chunked, 100 x (1000 x `"k":"<1000 a>",`) then `"z":1}` -> `HTTP/1.1 200`, `{"skuId":"BIG1","quantity":1}` in 0.2 s (loopback). `POST /inventory/A1` with a 4097-byte Content-Length body (valid JSON plus spaces) -> 400.
- Suggested fix: apply CappedBodyRequest to the unversioned writes as well (the PROMPT froze it, so this may be a DECISION CHALLENGE for the owner: the cap is a contract-visible 400 for CL and none for chunked, inconsistent).

### F-3-06 [NIT] `after` is not "compared as a plain string": a NUL byte truncates it
- Location: GET /inventory and /v2/inventory `after` (R4: never validated, plain string).
- What is wrong: `after=P0100%00x` behaves as `after=P0100` and `after=%00` behaves as the empty cursor, presumably the driver/Postgres C-string handling of U+0000. Not a 500, no data issue, but the rule and the OpenAPI text say it is compared as a plain string.
- Evidence: `curl -s 'localhost:18203/inventory?after=P0100%00x'` starts at `P0101` (same as `after=P0100`); `after=%00` returns the list from the first SKU.
- Suggested fix: none needed beyond a sentence in the docs, or reject U+0000 in `after` with 400.

### F-3-07 [NIT] Undecodable query string is only rejected when Tomcat is asked for parameters
- Location: C1/Z3 "an undecodable query string gets 400 text/plain on every path".
- What is wrong: on paths that never read parameters the query is ignored: `/nope?x=%ZZ` -> 404 "Not Found" and `/v3/api-docs?x=%ZZ` -> 200. Harmless, but the wording "every path" is not literally true (it is true for /inventory, /v2/inventory, /actuator/health).
- Evidence: `curl -si 'localhost:18203/nope?x=%ZZ'` -> `404 text/plain Not Found`; `curl -si 'localhost:18203/v3/api-docs?x=%ZZ'` -> `200`; `curl -si 'localhost:18203/actuator/health?x=%ZZ'` -> `400 text/plain Invalid request`.
- Suggested fix: reword the rule ("on every path that reads its query").

### F-3-08 [NIT] Small inconsistencies between the responses and the OpenAPI files / among error Content-Types
- `Content-Type` of errors is `text/plain` from the advice but `text/plain;charset=UTF-8` from the guard filter, Tomcat valve and the `;`/matrix answers (e.g. `GET /inventory/A;x=1`, `POST` with body > 4096, bad percent-escapes). Tests asserting equality with `text/plain` would need to be lenient; clients are unaffected.
- openapi-v2.yaml does not document the `Cache-Control: no-store` header that GET item, GET list, and PUT details 200/201 always send (verified on the wire: `Cache-Control: no-store` with `ETag: "N"`), nor the 400 that Tomcat gives GET /v2/inventory/{skuId} for `%5C`/`%00`/bad escapes (Schemathesis: "Undocumented HTTP status code, Received 400, Documented 200, 404", curl `GET /v2/inventory/wQ%C3%A9%C3%BD%C2%86%5C%C2%A8%C3%B8q`).
- The unversioned openapi.yaml repeats the spec's `example: Insufficient inventory` on the 404 responses (it is the spec's own Error example; only a note).

## Checked and fine (all verified live)
- Both OpenAPI files: live `/v3/api-docs/inventory` and `/v3/api-docs/inventory-v2` equal the committed YAML (servers aside).
- Every documented response reproduced: v1 GET list/item 200/404, POST add 200/400, purchase 200/400 "Insufficient inventory"/400 "Invalid request"/404; v2 GET item 200 (ETag, no-store)/404, add 200/400, purchase 200/400/404, PUT details 200/201/400/412 with the exact 412 text, GET list 200/400, Link headers.
- Schemathesis findings other than F-3-03/F-3-08 were test artifacts (reused Idempotency-Key, empty `If-None-Match` header, `If-None-Match: *` on an existing SKU, unknown query parameter accepted).
- Unversioned POSTs reject ANY Idempotency-Key with 400 and write nothing: valid UUID, empty (`Idempotency-Key;`), lowercase header name, two header lines, with bad body, with bad skuId; stock unchanged afterwards; GET with a key is ignored.
- /v2 key rules: missing/empty/braces/urn:uuid:/no hyphens/quoted/comma-separated/two lines -> 400; uppercase hex accepted and replays the lowercase key; leading/trailing whitespace trimmed by Tomcat (works); reuse with different qty, sku or operation -> 400; replay of 200, 404, 400 "Insufficient inventory" returns stored bodies; unversioned reuse of a /v2 key -> 400; key at 23:59 replays, at 24:01 -> 400 (backdated in my own DB); overflow 400 stored and replayed.
- Check order: v2 bad body wins over missing key, key over skuId (add 400, purchase 400 for a bad key; 404 for valid key + bad sku), unversioned key check before sku (purchase with bad sku + key -> 400). Documented divergence confirmed: `;` skuId answered by the guard filter before @Valid and before the key check (`POST /inventory/A;x` with bad body/key -> 400; `.../A;x/purchase` -> 404).
- Text/plain for all of: unknown paths, root, `/inventory/`, PUT/DELETE/PATCH/TRACE on spec routes (405 with Allow, TRACE never echoes headers, also on /nope 404), raw TRACE, CONNECT (501), FOO method (405), HTTP/1.2 (505), Expect: foo (417), bad target, bad header name, no Host, duplicate Host, 70 KB request line, 70 KB header, malformed/invalid-UTF-8 percent escapes, `%00`/`%5C` in path, `%2F`, chunked+CL, bad/negative Content-Length, TE gzip (501), invalid UTF-8 body, 4097-byte body, 70 KB details body. Library paths keep Boot behaviour (actuator 405/404 JSON, empty 406 on unsupported Accept, springdoc unknown group 404 JSON, swagger-ui redirect).
- Accept matrix on POST (v1 and v2) and PUT details: application/json, `*/*`, `*/*;q=0.1`, `application/*+json`, `application/*;q=0, application/json` accepted; xml, text/*, `application/json;q=0`, `application/json;q=0, */*`, `*/*;q=0`, garbage Accept -> 400. GET ignores Accept (200 JSON for xml / q=0 / garbage).
- G13 strict JSON: strings for ints, 5.5, null, missing, arrays, objects, booleans, NaN, 1e400, leading zeros, `+5`, comments, trailing commas, single quotes, trailing tokens, `{...}{...}`, -1/0/-0, > int32, > int64, wrong-case property, non-JSON content types, missing body/content type, UTF-16 -> 400 text/plain; unknown properties, `__proto__` and a UTF-8 BOM accepted (BOM acceptance is standards-legal).
- Details PUT: 201/200, ETag "1"/"N", stock untouched, If-Match with weak/leading-zero/`""`/huge/multi-line/multiple tags (412), malformed values (`2`, `"2`, `"3"x`, `"3" "3"`, trailing comma, `*, "3"`) -> 400, If-None-Match other than `*` -> 400, both headers -> 412, If-Match on missing SKU -> 412, empty header value -> 400, Idempotency-Key on PUT ignored, conditional GET headers ignored, POST/GET on /details 405 with Allow: PUT. Details field validation matrix (name, description, images, cost, nulls, surrogates, currency, int64 bounds) all as documented.
- Link header: correct format `<absolute URL>; rel="next"`, only `after` on v1 (limit ignored, extra params dropped), `limit`+`after` on v2 with the page size used, Link built from the routed path (`/%69nventory`, `/inventory;a=b`), no Link on the last page, always 250 max, `limit` lenient forms (0, -1, abc, empty, 9999, repeated, 2.0, 0x10, overflow -> 250; `%2B2` -> 2), repeated/undecodable/invalid-UTF-8 `after` -> 400. Host header is reflected in the Link (X-Forwarded-*/Forwarded ignored, out of scope).
- G12 overflow: seeded sku at Long.MAX-6, add 6 -> 200 at 9223372036854775807, add 1 -> 400 on v1 and v2, replay same.
- Path handling: `%2F`, `%2f`, `%252F`, `;`, `%3B`, `.`, `..`, unicode, 64/65-char skuIds, case-sensitivity, `/v2` routes with encoded prefixes (`/%76%32/...`) and matrix on literal segments behave per C1/C3.
