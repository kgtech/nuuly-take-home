# Critique — api (API contract and error handling) — v2 @ d01852a (reviewer report)

## Findings
- F-api-01 MAJOR — InventoryRequestGuardFilter bypassed by a percent-encoded prefix (`/%69nventory/ABC-1;lot=7` → 200 and a write on ABC-1; same for GET and for Accept q=0). Fix: routed path as JsonAcceptForGetFilter.routedPath (decoded PathSegment.valueToMatch), flag a segment whose raw value contains ';'; test through real Tomcat.
- F-api-02 MAJOR (predicted) — Boot's OrderedFormContentFilter parses form bodies on PUT/PATCH/DELETE; a bad escape (`a=%zz`) throws before the DispatcherServlet → Tomcat 500 → Boot's /error JSON body on /inventory/**. Repro: `curl -i -X DELETE localhost:8080/inventory/x -H 'Content-Type: application/x-www-form-urlencoded' --data 'a=%zz'`. Fix: `spring.mvc.formcontent.filter.enabled: false` and/or a text/plain ErrorController.
- F-api-03 MINOR — "undecodable query → 400 on every path" holds only where parameters are read: `GET /inventory/ABC-1?x=%zz` → 200, `GET /inventory?x=%zz` → 400; POSTs ignore it. Read the parameter map in a filter for /inventory/** or narrow the wording.
- F-api-04 NIT — Jackson accepts trailing tokens (`{"quantity":1}{"quantity":100}` → 200 with +1); `fail-on-trailing-tokens` off.
- F-api-05 NIT — openapi.yaml is 3.1.0 (spec 3.0.3), no named Error schema, examples dropped.
- F-api-06 NIT — InventoryItem (domain) imports a swagger @Schema annotation; PackageBoundaryTest allows it.

## Checked and found met
Spec table with exact bodies; G6 texts and reason phrases; G3/G13 strictness incl. overflow/boolean/array/exponent; G4/U3 order incl. keyed cases; G11/S2 without DB or Redis access, 64/65 boundary, %2F part of the id, case unchanged; S3/G8 key handling; S8/T1/Y3/Y4 and byte-identical keyed/unkeyed rendering; U2/Y1 on literal paths; Z3/R4/R8/C2 paging and Link; C1 valve, passthrough slash, TRACE, rejections, undecodable query with one WARN; S6 library paths and look-alikes; catch-all 500; OpenAPI export pins paths, codes, media types, patterns, defaults; G2/V2; G5; header names from constants.
