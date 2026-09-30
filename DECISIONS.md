# DECISIONS

Nuuly inventory API take-home. Generated from the decision board on 2026-09-29.
Each entry records my choice and my reasoning; rejected options list my reason, or the option's main drawback from research when I left it blank.

| ID | Type | Question | Choice | Matched recommendation |
|---|---|---|---|---|
| G1 | Spec gap | Are SKU IDs case-sensitive? | A: Case-sensitive, stored as sent | Yes |
| G11 | Spec gap | What characters, length and sort collation does a skuId get? | D: final: ASCII allowlist, 1–64 chars, COLLATE "C"; the guard filter answers ";" first; details PUT → 400 | Yes |
| G2 | Spec gap | How wide is quantity: 32-bit or 64-bit? | B: int64 (Java long, Postgres bigint) | No |
| G12 | Spec gap | What happens when an add would push stock past the bigint maximum (9,223,372,036,854,775,807)? | A: Guard in the SQL, no row → 400 | Yes |
| G3 | Spec gap | What status does a malformed or unsupported request get? | D: final: every client error → 400 text/plain on the POSTs and PUT details, except 412 for a failed precondition | Yes |
| G13 | Spec gap | How strictly is the JSON body parsed? | E: final: strict numbers (5.0 and 1e2 refused), strict duplicate names, ignore unknown fields, on every JSON body | Yes |
| G4 | Spec gap | On purchase, which wins: 404 (SKU missing) or 400 (bad body)? | C: 400 wins (Spring default) | No |
| G5 | Spec gap | What happens to a SKU that sells down to 0? | D: final: keep the row; GET returns 0; listed; sku_details rows are never deleted either | Yes |
| G6 | Spec gap | What exact text goes in error bodies? | D: final: the four texts plus the 412 text on PUT /v2 details only | Yes |
| G7 | Spec gap | What concurrency guarantee does purchase make? | A: Database-enforced: never oversell, never lose an add | Yes |
| G8 | Spec gap | Should the POST endpoints accept an Idempotency-Key, and is it required? | E: final: unversioned POSTs reject the key; /v2 stock POSTs require it | Yes |
| G14 | Spec gap | How does an Idempotency-Key behave on reuse, overlap and expiry? | D: final: /v2 only: same transaction, replay, spec codes only | Yes |
| G9 | Spec gap | How is the inventory list ordered and paged? | E: final: unversioned list fixed at 250 with after only; /v2 adds limit | Yes |
| G10 | Spec gap | Auth, and which HTTP status codes may the API return? | F: final: no auth; spec codes on the spec's operations, listed codes on /v2, standard HTTP elsewhere; library paths as coded | Yes |
| D0 | Design | Where do AI prompts and artifacts live in the repo? | A: agent-prompts.md + CLAUDE.md + DECISIONS.md | Yes |
| D1 | Design | Java and Spring Boot versions | A: Java 25 + Spring Boot 4.1.1 | Yes |
| D2 | Design | Build tool | A: Gradle wrapper 9.x (Kotlin DSL) | Yes |
| D3 | Design | Data access layer | A: Spring Data JPA (Hibernate 7.x), native queries for writes | No |
| D4 | Design | How do add and purchase stay correct under concurrency? | D: SERIALIZABLE isolation + retry | No |
| D5 | Design | How is the schema created and migrated? | F: final: Flyway migrations V1-V3 as built, V4+ only, V5 the ledger trigger; ddl-auto none | Yes |
| D6 | Design | How are errors turned into text/plain responses? | A: One @RestControllerAdvice returning text/plain | Yes |
| D7 | Design | Spec-first (generated) or code-first (springdoc)? | D: final: code-first + springdoc 3.1.x, two groups and two committed files | Yes |
| D8 | Design | How does a reviewer run it? | C: Both | Yes |
| D9 | Design | What does the test suite run against? | D: final: Testcontainers Postgres + concurrency tests through both versions | Yes |
| D10 | Design | Package layout and health endpoints | D: final: feature packages, package-private, web/ holds HttpConstants only, actuator health | Yes |
| R1 | Spec gap | When a request fails, what does its Idempotency-Key remember? | D: final: business outcomes as values; only /v2 stores them | Yes |
| R2 | Spec gap | Two requests arrive at the same moment with the same Idempotency-Key. What happens? | E: final: on /v2, INSERT … ON CONFLICT DO NOTHING, then re-read and replay | Yes |
| R3 | Spec gap | Does "every client error → 400" apply to the GET endpoints? | D: final: 400 on both versions' writes; framework codes outside the listed operations | Yes |
| R4 | Spec gap | What does GET /inventory do with a bad limit or after? | D: final: unversioned list ignores limit; /v2 leniency as before | Yes |
| R5 | Spec gap | G4's reasoning argues the opposite of its choice. Which one stands? | A: Keep G4-C; replace the note | Yes |
| R6 | Design | Gradle build script: Kotlin DSL or Groovy DSL? | A: Kotlin DSL (build.gradle.kts) | Yes |
| R7 | Spec gap | Confirm the skuId pattern | D: final: alphanumeric first, 1–64; the pattern applies to add | Yes |
| R8 | Spec gap | Confirm the maximum page size for limit | D: final: 250 is the maximum and default page on both versions; limit exists on /v2 only | Yes |
| R9 | Spec gap | Confirm how and when Idempotency-Keys expire | E: final: on /v2, 24h, expire on read (no deletes) | Yes |
| S1 | Design | How does the code run a write statement that returns a row (RETURNING), given that @Modifying queries cannot return rows? | F: final: JdbcClient statements in StockRepository, DetailsRepository and IdempotencyStore; the ledger row from V5 (superseded by E2) | Yes |
| S2 | Spec gap | Where is the skuId format checked, so that GET and purchase return 404 (not 400) for an ID that fails the pattern? | D: final: no constraint annotations on @PathVariable; the service checks skuId; the filter answers ";" first | Yes |
| S3 | Spec gap | What does a valid Idempotency-Key look like, and what happens to an empty or oversized one? | D: final: /v2 requires a UUID key; the unversioned POSTs reject any key | Yes |
| S4 | Design | How do reviewers start app + Postgres with Docker while bootRun starts only Postgres? | E: final: two files, app in compose.override.yaml on host port 8080 (APP_PORT overrides) | Yes |
| S5 | Spec gap | How do error responses guarantee a text/plain body when the client's Accept header asks for JSON? | A: Always set contentType(TEXT_PLAIN) explicitly | Yes |
| S6 | Spec gap | What does a 500 look like, and which URLs does the "spec codes only" rule cover? | E: final: catch-all → 500 text/plain; library paths keep library behaviour; unknown springdoc group is 404 | Yes |
| S7 | Design | Where does the one complete add statement (with the G12 overflow guard) and the purchase statement (with RETURNING) get written down? | A: One canonical statement in the D4 rules | Yes |
| S8 | Spec gap | Is an Idempotency-Key unique per SKU and endpoint, or across the whole API? | D: final: on /v2 the key is global: primary key = key | Yes |
| S9 | Design | Should DECISIONS.md show D8's options A and B as part of the chosen option, and should D0 cover the ai/ folder the repo already has? | A: List combined options as included; refine D0's rule | Yes |
| S10 | Design | Where are dependency versions pinned, and how are they written in the docs? | D: final: pin once in the catalog, range + "built with" in the docs; ArchUnit gets a catalog line | Yes |
| S11 | Design | Which shipped SQL statements and constraints must have a test against real Postgres, and how is that list kept? | D: final: every native query and constraint has a Testcontainers test, on both versions, plus concurrent adds | Yes |
| S12 | Design | What checks that the built API and the exported openapi.yaml still match the original spec's contract? | E: final: contract test per version, spec-only codes unversioned, conformance test against the spec | Yes |
| T1 | Spec gap | How is an expired Idempotency-Key reused without running the request twice? | E: final: on /v2, never reuse an expired key | Yes |
| T2 | Design | Your S2 note describes a body field that doesn't exist. Which wording stands? | A: Replace the S2 note with corrected wording | Yes |
| T3 | Spec gap | What body text do 404 (unknown path), 405 and 406 responses carry? | B: Standard reason phrase for any status G6 doesn't cover | Yes |
| T6 | Design | The plan is estimated at about 45.5h (35.6h before the ledger rounds) against a 24-hour limit. Where is the cut line? | E: final: superseded by H14 (5-hour budget, ai/final/plan.md) | Yes |
| U1 | Spec gap | When an add overflows (G12 400), what happens to its Idempotency-Key? | C: final: Overflow is an outcome and is stored on /v2 | Yes |
| U2 | Spec gap | What do the GET endpoints return for an unsupported Accept header? | D: final: GET ignores Accept on both versions; POST and PUT with an unacceptable Accept → 400 | Yes |
| U3 | Spec gap | On purchase, which check runs first: the Idempotency-Key format (400) or the skuId pattern (404)? | C: final: two orders, unversioned (key present → 400) and /v2 (key required) | Yes |
| V1 | Design | Should stock changes also be recorded in an append-only ledger? | C: Ledger only (balance = SUM of deltas) | No |
| V2 | Spec gap | With quantities stored as bigint/long, how wide is the request quantity, and what happens to the overflow guard? | A: Request int, stock bigint/long, keep the guard at the bigint max | Yes |
| W1 | Design | With the ledger as the source of truth, how does a write stay correct under concurrency? | B: SERIALIZABLE transactions with retry (D4 → D) | No |
| W2 | Design | How many times does a SERIALIZABLE write retry, and what happens when retries run out? | A: 10 retries with jittered backoff; exhaustion → 500 | Yes |
| X1 | Design | Where do the retry and the SERIALIZABLE transaction boundary sit in the code? | B: @Retryable method runs a TransactionTemplate | Yes |
| Y1 | Design | Where is a POST's Accept header checked, so an unacceptable Accept never changes stock? | C: final: produces = application/json on every write mapping of both versions | Yes |
| Y2 | Design | How does the retry tell a serialization failure (40001/40P01) from other lock failures? | A: Framework 7 predicate = MethodRetryPredicate | Yes |
| Y3 | Spec gap | What goes into the Idempotency-Key request hash? | C: final: SHA-256 of ("v2", operation, skuId, quantity) after parsing | Yes |
| Y4 | Design | How does a replayed response get its Content-Type? | C: final: store content_type with status and body; /v2 replays a SkuItem | Yes |
| Z1 | Design | Where do Idempotency-Key handling, input checks and the idempotency transaction sit? | B: Service-layer @Idempotent interceptor | Yes |
| Z2 | Design | How is the inventory feature split between web and domain code? | D: final: domain package + web sub-package, listed from the code (as built) | Yes |
| Z3 | Spec gap | What does GET /inventory return for a query string it can't read unambiguously? | E: final: 400 "Invalid request" on both lists; every path that reads its query | Yes |
| C1 | Spec gap | How are errors that Spring MVC never sees, and errors on library paths, rendered? | F: final: text/plain valve, %2F passthrough, TRACE, /error and bad chunk framing text/plain, advice declines library paths | Yes |
| C2 | Spec gap | What does GET /inventory return when no limit is given? | F: final: fixed 250 page and after-only Link unversioned; limit, limit Link and skuId schema on /v2 (classes as built) | Yes |
| C3 | Spec gap | What does the API do with ";" in the skuId segment and with an Accept that gives JSON q=0? | D: final: guard filter, driven by route kind, for every route of both versions; first listed range wins | Yes |
| E1 | Design | Where does stock live, and how does a write stay correct under concurrency without retries? | G: final: balance row, conditional UPDATE at READ COMMITTED; the ledger row comes from the V5 trigger (A14) | Yes |
| E2 | Design | How does the code run SQL once nothing needs Spring Data JPA? | D: final: JdbcClient only, in three places | Yes |
| E3 | Design | How does an existing v1 database move to the balance row, and how is the ledger kept append-only? | G: final: V1 carries the balance row and triggers; append-only holds against the API; V5 records every balance change (A14) | Yes |
| A11 | Design | Which error does the append-only trigger raise? | A: P0001 from RAISE EXCEPTION | Yes |
| A14 | Design | Is quantity = SUM(quantity_delta) kept by the database or asserted by tests? | E: final: Postgres writes the ledger row from the balance change (V5) | Yes |
| A18 | Spec gap | What does a keyed POST get when its key's row is committed without a stored response? | C: final: on /v2, 400 "Invalid request" for a row with no stored response | Yes |
| A33 | Design | Where does Idempotency-Key handling sit once nothing needs ordering against a retry? | D: final: explicit IdempotencyStore.run on the keyed /v2 entry points only | Yes |
| A34 | Spec gap | In what order are a POST's body, Idempotency-Key and skuId checked, and where? | E: final: unversioned order rejects the key; /v2 order requires it; each check once (tests as they are) | Yes |
| A37 | Design | Where does app-wide HTTP code live, and which types and members are public? | E: final: HTTP code stays in inventory.web (package-private); web/ holds HttpConstants only; classes as built | Yes |
| A38 | Design | What does each write return, and how does the controller render it? | E: final: sealed non-generic WriteResult; typed WriteResult<O> and one Page<T> were designed, not built | Yes |
| A39 | Design | How is the package layout checked on the compiled classes? | D: final: ArchUnit core with the rules that hold on the real layout | Yes |
| H1 | Spec gap | How are the spec and the extensions versioned? | E: final: path prefix; the spec's URLs stay byte for byte; "compatible" defined; removal only by an owner decision | Yes |
| H2 | Spec gap | Are /v2 stock writes keyed, and is the key required? | A: Idempotency-Key required on /v2 add and purchase | Yes |
| H3 | Spec gap | What does an unversioned POST do with an Idempotency-Key? | A: Reject any present header with 400 and change nothing | Yes |
| H4 | Spec gap | What is the unversioned list's page size? | A: Fixed 250, only after; the Link carries only after | Yes |
| H5 | Spec gap | How does /v2 list? | A: limit 1–250 (lenient, default 250) and after; Link with both | Yes |
| H6 | Spec gap | Where do the spec operations appear under /v2, and where do details go? | A: /v2 mirrors the spec operations; details are PUT …/details | Yes |
| H7 | Spec gap | How does the details PUT create and replace? | D: final: PUT creates (201) or replaces (200); If-Match and If-None-Match: *; 412; costs recorded | Yes |
| H8 | Spec gap | What is the 412 text? | A: One fixed text for every 412 | Yes |
| H9 | Design | What does V4 do to the idempotency CHECKs for 'create' and 201/409? | A: V4 narrows both CHECKs with NOT VALID | Yes |
| H10 | Spec gap | Does the request hash include the API version? | A: Yes: hash input "v2" + operation + skuId + quantity | Yes |
| H11 | Spec gap | What drives the guard filter? | C: final: route-kind guard filter with the body cap counted while read on every write route | Yes |
| H12 | Design | How is OpenAPI documented across the two versions? | A: Two springdoc groups, two committed files, plus a conformance test against the spec | Yes |
| H13 | Design | Which host ports do the app and the dev server use? | A: App on host 8080, Vite on 5173 (main's) | Yes |
| H14 | Design | What order and budget does the final build follow? | A: Follow ai/final/plan.md; stop and push what is green at the end of the budget | Yes |
| H15 | Design | Which architecture guards does the final build add? | A: ArchUnit with only the rules that hold on the real layout | Yes |
| H16 | Spec gap | Does the guard filter run before body validation? (M-13) | A: Freeze it: the guard filter answers before the body is validated | Yes |
| A17 | Spec gap | What format does the Idempotency-Key have, and does a replay say so? | A: UUID; no replay header | Yes |
| A19 | Spec gap | How large may a write body be? | C: final: 4096 bytes on both POSTs of both versions (chunked counted while read), 65536 on PUT details | Yes |
| A22 | Design | Where do SKU details live? | A: Own table sku_details (PK sku_id, version) | Yes |
| A25 | Spec gap | What format do /v2 errors have? | A: text/plain fixed texts through the S5 helper | Yes |
| A26 | Spec gap | What are the cost and SkuDetails field rules? | A: Integer minor units with an ISO currency; bounded name, description and images | Yes |
| A27 | Design | How is a /v2 read served? | C: final: one join, no transaction; ETag is the details version only; no-store; conditional GET ignored | Yes |
| A30 | Design | Does the service use any store besides Postgres? | A: Postgres only | Yes |
| A35 | Design | Does the idempotency row store a domain outcome instead of the HTTP response? | A: Keep the rendered response (deferred #83) | Yes |
| A36 | Design | Does the service use a message broker? | A: No broker | Yes |
| H17 | Design | Which server-side time bounds does the service set? | A: Hikari connection-timeout 3000 ms and Postgres lock_timeout 5 s | Yes |

## G1: Are SKU IDs case-sensitive?

- **Type:** Spec gap
- **Choice:** A: Case-sensitive, stored as sent
- **My reasoning:** Shopify uses case-sensitive SKUs, and the examples `widget` and `CW-XYCS-BM-01` indicate case sensitivity should be preserved for this project as well.
- **Rejected:**
  - B: Case-insensitive, normalized to upper case. Drawback noted in research: Responses return a different skuId than the client sent.
- **Matched recommendation:** Yes

## G11: What characters, length and sort collation does a skuId get?

- **Type:** Spec gap
- **Choice:** D: final: ASCII allowlist, 1–64 chars, COLLATE "C"; the guard filter answers ";" first; details PUT → 400
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: ASCII allowlist, 1–64 chars, COLLATE "C". Superseded: the answers now name /v2 and PUT details, and the ";" check belongs to the guard filter, not to the controllers.
  - B: Any printable string up to 255, no slash, COLLATE "C". Drawback noted in research: URL-encoding cases (spaces, %2F, Unicode) need tests.
  - C: Any non-blank string, database default collation. Drawback noted in research: Sort order depends on the database's locale (en_US puts a before B; C puts B first).
- **Matched recommendation:** Yes

## G2: How wide is quantity: 32-bit or 64-bit?

- **Type:** Spec gap
- **Choice:** B: int64 (Java long, Postgres bigint)
- **My reasoning:** Use bigint/long so we don't have to defensively program against issues in backend calculations. (Changed from int32 after the ledger discussion.)
- **Rejected:**
  - A: int32 (Java int, Postgres integer). Drawback noted in research: Needs an overflow rule for adds (see G12).
- **Matched recommendation:** No
- **Refined by:** V2
- **Current rules (after refinement):**
  - Stored and returned quantity is long in Java and bigint in Postgres; request quantity is Integer (V2). (refined by V2)

## G12: What happens when an add would push stock past the bigint maximum (9,223,372,036,854,775,807)?

- **Type:** Spec gap
- **Choice:** A: Guard in the SQL, no row → 400
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Let Postgres raise 22003 and map it. Drawback noted in research: 22003 and 23514 arrive as the same Spring exception, so it needs SQLState inspection.
  - C: Check in Java with Math.addExact. Drawback noted in research: Only safe with a lock or version check (D4 B or C).
- **Matched recommendation:** Yes
- **Refined by:** S7, V2, W1, E1
- **Current rules (after refinement):**
  - Adds that would exceed 9223372036854775807 are rejected by the add's conditional UPDATE … WHERE quantity <= 9223372036854775807 - :q (E1). No row returned → 400 "Invalid request". (refined by S7, V2, W1, E1)

## G3: What status does a malformed or unsupported request get?

- **Type:** Spec gap
- **Choice:** D: final: every client error → 400 text/plain on the POSTs and PUT details, except 412 for a failed precondition
- **My reasoning:** Follow-up 1: the 400 rule names the 412 of a failed precondition as its only exception.
- **Rejected:**
  - A: All client request errors → 400 text/plain. Superseded: the rule now names /v2 and the details PUT beside the spec POSTs.
  - B: Spec'd cases → 400; Spring defaults elsewhere. Drawback noted in research: Returns codes the spec doesn't list.
  - C: final: all client request errors → 400 text/plain on both versions' POSTs and on PUT details. Superseded by follow-up 1: "every client error is 400" contradicted H7 and H8 (412).
- **Matched recommendation:** Yes

## G13: How strictly is the JSON body parsed?

- **Type:** Spec gap
- **Choice:** E: final: strict numbers (5.0 and 1e2 refused), strict duplicate names, ignore unknown fields, on every JSON body
- **My reasoning:** Critique M-11: the last of two duplicate names won ({"quantity":1,"quantity":2} added 2), so a parser differential could change the amount; strict detection is adopted as a new check. Critique M-16: refusing 5.0 and 1e2 is deliberate (the spec's quantity is an integer written as digits).
- **Rejected:**
  - A: Strict numbers, ignore unknown fields. Superseded: the rule now names the /v2 bodies and the details body.
  - B: Strict numbers and reject unknown fields. Drawback noted in research: The schema doesn't set additionalProperties: false, so this is stricter than the contract.
  - C: Jackson defaults. Drawback noted in research: May accept "10" and truncate 1.5 (unverified).
  - D: final: strict numbers, ignore unknown fields, on every write body. Superseded by critique M-11 and M-16: duplicate names were accepted (last one won), and the refusal of 5.0 and 1e2 was undocumented.
- **Matched recommendation:** Yes

## G4: On purchase, which wins: 404 (SKU missing) or 400 (bad body)?

- **Type:** Spec gap
- **Choice:** C: 400 wins (Spring default)
- **My reasoning:** Validation runs first (Spring default). A malformed request is rejected before any lookup. The only overlap is an invalid body on a missing SKU, and this costs no code.
- **Rejected:**
  - A: 404 wins over everything. Drawback noted in research: Bind the body as a String/JsonNode and parse by hand.
  - B: 404 wins over quantity rules; malformed JSON is still 400. Drawback noted in research: Malformed JSON or wrong Content-Type on a missing SKU still returns 400.
  - D: 404 wins; message lists every problem. Drawback noted in research: The message is no longer one of the fixed strings.
- **Matched recommendation:** No

## G5: What happens to a SKU that sells down to 0?

- **Type:** Spec gap
- **Choice:** D: final: keep the row; GET returns 0; listed; sku_details rows are never deleted either
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Keep the row; GET returns 0; listed. Superseded: V3 adds sku_details, whose rows are never deleted either.
  - B: Keep the row; hide from the list. Drawback noted in research: GET and list disagree.
  - C: Delete the row at 0. Drawback noted in research: A purchase can turn a SKU into a 404.
- **Matched recommendation:** Yes

## G6: What exact text goes in error bodies?

- **Type:** Spec gap
- **Choice:** D: final: the four texts plus the 412 text on PUT /v2 details only
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Fixed strings from the spec descriptions. Superseded by H8: the 412 text joins the fixed texts.
  - B: Fixed prefix plus field detail. Drawback noted in research: More strings to test.
  - C: Free text, including counts. Drawback noted in research: Leaks stock levels, which you ruled out.
- **Matched recommendation:** Yes

## G7: What concurrency guarantee does purchase make?

- **Type:** Spec gap
- **Choice:** A: Database-enforced: never oversell, never lose an add
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: In-JVM lock per SKU. Drawback noted in research: Breaks as soon as a second instance runs.
  - C: No explicit guarantee. Drawback noted in research: Oversells under load.
- **Matched recommendation:** Yes
- **Refined by:** W1, E1
- **Current rules (after refinement):**
  - Stock correctness is enforced by Postgres in one READ COMMITTED transaction, never by a Java-side check alone: the conditional UPDATE … WHERE quantity >= :q and CHECK (quantity >= 0) on sku.quantity are the guard, and nothing retries (E1). (refined by W1, E1)

## G8: Should the POST endpoints accept an Idempotency-Key, and is it required?

- **Type:** Spec gap
- **Choice:** E: final: unversioned POSTs reject the key; /v2 stock POSTs require it
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: Optional header on both POSTs. Superseded by OD-3 and OD-4: the spec has no key, so the unversioned POSTs reject it and /v2 requires it.
  - B: Required header on both POSTs. Drawback noted in research: Every request built from the original spec gets 400.
  - C: Optional header on purchase only. Drawback noted in research: Retried adds still double-count.
  - D: No idempotency. Drawback noted in research: Double-counting on retries.
- **Matched recommendation:** Yes

## G14: How does an Idempotency-Key behave on reuse, overlap and expiry?

- **Type:** Spec gap
- **Choice:** D: final: /v2 only: same transaction, replay, spec codes only
- **My reasoning:** Owner OD-3 and OD-4. One 400, like the spec (critique M-07): a reused key with a different request and an expired key both answer 400 "Invalid request", because the spec's POSTs list one client-error code; an in-flight duplicate does not answer an error at all, it waits on the first request's row and replays its response (the draft's 409 is not used). Accepted cost: the client cannot tell mismatch and expiry apart. The old reason for rejecting 409/422 ("adds status codes the spec does not list") no longer holds, because /v2 also answers 412. Comparison: IETF draft-ietf-httpapi-idempotency-key-header revision -07 (2025-10-15, "Expired & archived"; https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/) says SHOULD reply 422 for a different payload, 409 for an in-flight duplicate and 400 for a missing key; it is not a standard and has expired. Stripe (https://docs.stripe.com/api/idempotent_requests) answers a reused key with different parameters with a 400-class idempotency error.
- **Rejected:**
  - A: Same transaction, replay, spec codes only. Superseded by OD-3: only /v2 writes touch the table.
  - B: IETF draft codes (409 / 422). The real reason is one 400 for every request problem, like the spec's single client-error code; the client cannot tell mismatch and expiry apart (accepted); an in-flight duplicate waits and replays instead of answering 409. The earlier reason (status codes the spec does not list) fails because /v2 answers 412 too. Draft revision -07 (expired and archived) says 422 for a different payload, 409 for an in-flight duplicate, 400 for a missing key; Stripe answers a mismatched reuse with a 400-class idempotency error (critique M-07).
  - C: Replay successes only. Drawback noted in research: Two stores of truth for the same key over time.
- **Matched recommendation:** Yes

## G9: How is the inventory list ordered and paged?

- **Type:** Spec gap
- **Choice:** E: final: unversioned list fixed at 250 with after only; /v2 adds limit
- **My reasoning:** Owner OD-5. Accepted risk (critique M-09): a client that ignores Link sees 250 of N SKUs with no truncation signal; the cap is disclosed in openapi.yaml and the README.
- **Rejected:**
  - A: Sorted by skuId, no paging. Drawback noted in research: Unbounded response.
  - B: Opt-in keyset paging, bare array, Link header. Superseded by OD-5: opt-in limit on the unversioned list is now a /v2 extension; the unversioned page is fixed at 250.
  - C: Opt-in offset paging (page, size). Drawback noted in research: OFFSET cost grows; rows shift between pages when stock is added.
  - D: Paged by default (e.g. 50). Superseded by OD-5: a default page size is what OD-5 now does on GET /inventory (fixed 250, after only). It was rejected earlier because a spec client would no longer get every SKU; it is accepted now because an unbounded list ran out of memory at 1M SKUs (C-02).
- **Matched recommendation:** Yes

## G10: Auth, and which HTTP status codes may the API return?

- **Type:** Spec gap
- **Choice:** F: final: no auth; spec codes on the spec's operations, listed codes on /v2, standard HTTP elsewhere; library paths as coded
- **My reasoning:** Follow-up 13 and critique M-02.
- **Rejected:**
  - A: No auth; only spec status codes. Drawback noted in research: Not production-safe (documented).
  - B: No auth; framework defaults. Drawback noted in research: Codes outside the contract.
  - C: Static API key header. Drawback noted in research: Adds setup for reviewers.
  - D: No auth; spec codes on the spec's operations, standard HTTP elsewhere. Superseded: /v2/inventory/** is named next to /inventory/**, with its own listed codes.
  - E: final: no auth; spec codes on the spec's operations, listed codes on /v2, standard HTTP elsewhere. Superseded by follow-up 13 and critique M-02: the library-path list and the two 404s follow the code.
- **Matched recommendation:** Yes

## D0: Where do AI prompts and artifacts live in the repo?

- **Type:** Design choice
- **Choice:** A: agent-prompts.md + CLAUDE.md + DECISIONS.md
- **My reasoning:** Prompts live in agent-prompts.md: the prompt, summary of output, what was accepted, what was rejected, summary of my response to the output.
- **Rejected:**
  - B: One file per session under /ai. Drawback noted in research: Harder to skim.
  - C: Raw chat exports only. Drawback noted in research: Doesn't show what you changed or rejected.
- **Matched recommendation:** Yes
- **Refined by:** S9
- **Current rules (after refinement):**
  - After each AI session, append to agent-prompts.md: prompt, output summary, what was accepted, what was rejected, your response.
  - Keep CLAUDE.md, DECISIONS.md and agent-prompts.md at the repo root. Supporting AI artifacts (decision board, decision review, research sources) go in ai/. (refined by S9)

## D1: Java and Spring Boot versions

- **Type:** Design choice
- **Choice:** A: Java 25 + Spring Boot 4.1.1
- **My reasoning:** Java 25 with Spring Boot 4.1.x. Java 25 is LTS and includes compact source files, instance main methods, module import declarations, flexible constructor bodies, and scoped values for virtual threads. Production builds are available from major vendors.
- **Rejected:**
  - B: Java 21 + Spring Boot 4.1.1. Drawback noted in research: No reason to go older.
  - C: Java 25 + Spring Boot 4.0.x. Drawback noted in research: Misses 4.1 fixes.
- **Matched recommendation:** Yes
- **Refined by:** S10, E2
- **Current rules (after refinement):**
  - Java 25 toolchain, Spring Boot 4.1.x (built with 4.1.1; set in gradle/libs.versions.toml) (Spring Framework 7, Jackson 3 under tools.jackson; no JPA or Hibernate ORM (E2); Hibernate Validator, from the Spring Boot BOM, stays for @Valid). Use spring-boot-starter-webmvc, not -web. (refined by S10, E2)

## D2: Build tool

- **Type:** Design choice
- **Choice:** A: Gradle wrapper 9.x (Kotlin DSL)
- **My reasoning:** Gradle.
- **Rejected:**
  - B: Maven wrapper. Drawback noted in research: Slower, more verbose.
- **Matched recommendation:** Yes
- **Refined by:** S10
- **Current rules (after refinement):**
  - Use the Gradle wrapper, version 9.1+ (Java 25 needs it; pinned in gradle-wrapper.properties), with the Kotlin DSL. (refined by S10)

## D3: Data access layer

- **Type:** Design choice
- **Choice:** A: Spring Data JPA (Hibernate 7.x), native queries for writes
- **My reasoning:** Spring Data JPA.
- **Rejected:**
  - B: JdbcClient with hand-written SQL. Drawback noted in research: Manual row mapping.
  - C: jOOQ. Drawback noted in research: Code generation setup eats time.
- **Matched recommendation:** No
- **Refined by:** S1, W1, X1, Z1, E2
- **Current rules (after refinement):**
  - Superseded by E2: all SQL runs through JdbcClient in StockRepository, DetailsRepository and IdempotencyStore; there is no ORM, repository fragment or @Modifying. (refined by S1, W1, X1, Z1, E2)

## D4: How do add and purchase stay correct under concurrency?

- **Type:** Design choice
- **Choice:** D: SERIALIZABLE isolation + retry
- **My reasoning:** This is the initial approach. A production system could have a cart service and even a reservation service that holds inventory for a set short period of time. Similar to purchasing ibventory from Ticketmaster the inventory is reserved for x minutes then released if not purchased. Concurrency design history (critique M-10): the ledger-SUM design with SERIALIZABLE transactions and retries was built first (V1-C, W1-B, D4-D); #26 showed 40001 serialization failures on writes to different SKUs; it was replaced by a balance row plus the append-only ledger at READ COMMITTED (E1, which supersedes W1, W2, X1, Y2 and Z1). This card stays chosen so the supersession is visible.
- **Rejected:**
  - A: Atomic conditional UPDATE + ON CONFLICT upsert. Drawback noted in research: 0 rows is ambiguous: one extra SELECT to choose 404 vs 400.
  - B: Pessimistic row lock. Drawback noted in research: Two concurrent creates of a new SKU still race (needs ON CONFLICT or retry).
  - C: Optimistic @Version + retry. Drawback noted in research: Hot SKUs retry a lot.
- **Matched recommendation:** No
- **Refined by:** S1, S7, V2, W1, E1
- **Current rules (after refinement):**
  - Stock writes run at READ COMMITTED under the sku row's lock; nothing retries (E1). (refined by W1, E1)

## D5: How is the schema created and migrated?

- **Type:** Design choice
- **Choice:** F: final: Flyway migrations V1-V3 as built, V4+ only, V5 the ledger trigger; ddl-auto none
- **My reasoning:** A14 (V5): the migration list gains V5.
- **Rejected:**
  - A: Flyway migrations + ddl-auto=validate. Superseded: the card described migrations (a V3 balance row) that the code does not have.
  - B: Hibernate ddl-auto=update. Drawback noted in research: Tests never run reviewed DDL.
  - C: schema.sql via spring.sql.init. Drawback noted in research: No versioning.
  - D: Liquibase. Drawback noted in research: More ceremony than needed.
  - E: final: Flyway migrations V1-V3 as built, V4+ only; ddl-auto none. Superseded by A14: V5 adds the ledger trigger.
- **Matched recommendation:** Yes

## D6: How are errors turned into text/plain responses?

- **Type:** Design choice
- **Choice:** A: One @RestControllerAdvice returning text/plain
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Handle errors in each controller. Drawback noted in research: Framework exceptions (parse, media type) still escape.
  - C: ProblemDetail JSON (RFC 9457). Drawback noted in research: Spec requires text/plain.
- **Matched recommendation:** Yes
- **Refined by:** R1, S5, A38
- **Current rules (after refinement):**
  - One @RestControllerAdvice maps every thrown error (parsing, validation, framework) to ResponseEntity<String> built by one helper that sets .contentType(MediaType.TEXT_PLAIN) explicitly (never left to content negotiation); the controller's 404 and 400 responses take their status, Content-Type and body from the same helper, directly or, for a write's outcome, through OutcomeResponses and StoredResponses (A38). Keep ProblemDetail off. (refined by R1, S5, A38)

## D7: Spec-first (generated) or code-first (springdoc)?

- **Type:** Design choice
- **Choice:** D: final: code-first + springdoc 3.1.x, two groups and two committed files
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Code-first + springdoc 3.1.x. Superseded by H12: two documents replace the single openapi.yaml.
  - B: Spec-first with openapi-generator ≥ 7.20. Drawback noted in research: Boot 4 support is new; one bug's fix is unconfirmed.
  - C: Code-first + contract check against the original spec. Drawback noted in research: Another hour.
- **Matched recommendation:** Yes

## D8: How does a reviewer run it?

- **Type:** Design choice
- **Choice:** C: Both
- **My reasoning:** _No notes recorded._
- **Included in the choice:** A: docker compose up (app + Postgres); B: ./gradlew bootRun with Boot Docker Compose support
- **Rejected:** none
- **Matched recommendation:** Yes
- **Refined by:** S4
- **Current rules (after refinement):**
  - compose.yaml runs Postgres and compose.override.yaml adds the app, so `docker compose up --build` runs both for reviewers; spring-boot-docker-compose (developmentOnly) for bootRun. (refined by S4)

## D9: What does the test suite run against?

- **Type:** Design choice
- **Choice:** D: final: Testcontainers Postgres + concurrency tests through both versions
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Testcontainers Postgres + a concurrency test. Superseded: invariant 1 covers both versions.
  - B: H2 in PostgreSQL mode. Drawback noted in research: Doesn't run the same SQL (unverified which parts fail).
  - C: Mock the repository. Drawback noted in research: Never runs SQL; can't prove concurrency.
- **Matched recommendation:** Yes

## D10: Package layout and health endpoints

- **Type:** Design choice
- **Choice:** D: final: feature packages, package-private, web/ holds HttpConstants only, actuator health
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Feature packages, package-private, actuator health. Superseded: the app-wide web/ package and package-private Operation.dbValue() are not in the code.
  - B: Layered packages + actuator health. Drawback noted in research: Everything must be public.
  - C: Feature packages, no actuator. Drawback noted in research: Compose has no health check to wait on.
- **Matched recommendation:** Yes

## R1: When a request fails, what does its Idempotency-Key remember?

- **Type:** Spec gap
- **Choice:** D: final: business outcomes as values; only /v2 stores them
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: Replay successes only. Drawback noted in research: Changes G14 from A to C, and the README wording.
  - B: Store business outcomes as values (Stripe model). Superseded by OD-3: storing is a /v2 behaviour.
  - C: Keep throwing; record failures in a separate transaction. Drawback noted in research: Two transactions per failure.
- **Matched recommendation:** Yes

## R2: Two requests arrive at the same moment with the same Idempotency-Key. What happens?

- **Type:** Spec gap
- **Choice:** E: final: on /v2, INSERT … ON CONFLICT DO NOTHING, then re-read and replay
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: INSERT … ON CONFLICT DO NOTHING, then re-read and replay. Superseded by OD-3: scoped to /v2.
  - B: Catch the unique violation and retry in a new transaction. Drawback noted in research: Exception-driven; needs SQLState checks.
  - C: Reject the second request with 400. Drawback noted in research: A legitimate retry gets an error.
  - D: Advisory lock on the key first. Drawback noted in research: Extra round trip on every keyed request.
- **Matched recommendation:** Yes

## R3: Does "every client error → 400" apply to the GET endpoints?

- **Type:** Spec gap
- **Choice:** D: final: 400 on both versions' writes; framework codes outside the listed operations
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: 400 on the POSTs only; framework codes outside the contract. Superseded: /v2 writes and the details PUT are named with the spec POSTs.
  - B: Only spec codes, everywhere. Drawback noted in research: DELETE on an existing SKU returns 404, which is misleading.
  - C: Keep G3 as written: 400 everywhere. Drawback noted in research: GET returns a code the spec doesn't list.
- **Matched recommendation:** Yes

## R4: What does GET /inventory do with a bad limit or after?

- **Type:** Spec gap
- **Choice:** D: final: unversioned list ignores limit; /v2 leniency as before
- **My reasoning:** Owner OD-5
- **Rejected:**
  - A: Lenient, always 200. Superseded by OD-5: limit leniency moves to /v2; the unversioned list ignores limit entirely.
  - B: Coerce large values, 400 for nonsense. Drawback noted in research: Adds a 400 to an operation that lists only 200.
  - C: Strict 400 for any bad value. Drawback noted in research: Adds a 400 the spec doesn't list.
- **Matched recommendation:** Yes

## R5: G4's reasoning argues the opposite of its choice. Which one stands?

- **Type:** Spec gap
- **Choice:** A: Keep G4-C; replace the note
- **My reasoning:** We can leverage spring validation for free without doing a lookup.
- **Rejected:**
  - B: Switch G4 to B; keep 404-first. Drawback noted in research: About 1h: no @Valid on purchase, manual Validator after the lookup.
  - C: Switch G4 to A; 404 before any parsing. Drawback noted in research: About 2h: raw body handling that fights Spring.
- **Matched recommendation:** Yes

## R6: Gradle build script: Kotlin DSL or Groovy DSL?

- **Type:** Design choice
- **Choice:** A: Kotlin DSL (build.gradle.kts)
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Groovy DSL (build.gradle). Drawback noted in research: No type checking in the IDE.
- **Matched recommendation:** Yes

## R7: Confirm the skuId pattern

- **Type:** Spec gap
- **Choice:** D: final: alphanumeric first, 1–64; the pattern applies to add
- **My reasoning:** Follow-up 5: "add", not "create".
- **Rejected:**
  - A: Alphanumeric first, 1–64: ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$. Superseded by follow-up 5: "POST create" no longer exists after OD-6.
  - B: Keep ^[A-Za-z0-9._-]{1,64}$ and reject "." and ".." explicitly. Drawback noted in research: Special cases in code.
  - C: Alphanumeric first, up to 255. Drawback noted in research: Longer keys for no stated need.
- **Matched recommendation:** Yes

## R8: Confirm the maximum page size for limit

- **Type:** Spec gap
- **Choice:** D: final: 250 is the maximum and default page on both versions; limit exists on /v2 only
- **My reasoning:** Owner OD-5
- **Rejected:**
  - A: 1–1000. Drawback noted in research: Larger than the commerce APIs above.
  - B: 1–250 (Shopify). Superseded by OD-5: limit is a /v2 parameter only.
  - C: 1–100 (GitHub, Stripe). Drawback noted in research: More requests for large inventories.
- **Matched recommendation:** Yes

## R9: Confirm how and when Idempotency-Keys expire

- **Type:** Spec gap
- **Choice:** E: final: on /v2, 24h, expire on read (no deletes)
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: 24h, purge on write. Drawback noted in research: Extra statement on every keyed write.
  - B: 24h, expire on read (no deletes). Superseded by OD-3: scoped to /v2.
  - C: 24h, scheduled cleanup. Drawback noted in research: A scheduler to configure and test.
  - D: No expiry. Drawback noted in research: A client can never reuse a key.
- **Matched recommendation:** Yes

## S1: How does the code run a write statement that returns a row (RETURNING), given that @Modifying queries cannot return rows?

- **Type:** Design choice
- **Choice:** F: final: JdbcClient statements in StockRepository, DetailsRepository and IdempotencyStore; the ledger row from V5 (superseded by E2)
- **My reasoning:** A14 (V5): the ledger row is no longer a JdbcClient write.
- **Rejected:**
  - A: Plain native @Query without @Modifying. Drawback noted in research: Relies on undocumented behaviour: Spring Data closed #2270 as invalid, and nothing states that Hibernate 7.1 runs DML through getResultList. Needs a spike test before building on it..
  - B: Repository fragment using JdbcClient for writes. Superseded by E2, now with three SQL places.
  - C: EntityManager.createNativeQuery in a custom fragment. Drawback noted in research: Same unconfirmed Hibernate behaviour as option A (DML through getResultList). JPA only forbids getResultList for JPQL UPDATE/DELETE and says nothing about native DML..
  - D: Drop RETURNING: @Modifying row count, then SELECT. Drawback noted in research: Two round trips on every successful add and purchase..
  - E: final: JdbcClient statements in StockRepository, DetailsRepository and IdempotencyStore (superseded by E2). Superseded by A14: StockRepository no longer inserts ledger rows.
- **Matched recommendation:** Yes

## S2: Where is the skuId format checked, so that GET and purchase return 404 (not 400) for an ID that fails the pattern?

- **Type:** Spec gap
- **Choice:** D: final: no constraint annotations on @PathVariable; the service checks skuId; the filter answers ";" first
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: No format check on GET and purchase; lookup returns 404. Drawback noted in research: A bad ID costs a database round trip..
  - B: @Pattern everywhere, map HandlerMethodValidationException per endpoint. Drawback noted in research: Body errors on these methods move from MethodArgumentNotValidException to HandlerMethodValidationException. The handler must inspect getParameterValidationResults() and the method (getMethod()) to keep G4-C. Easy to get wrong..
  - C: Plain check in the controller before the lookup. Superseded: the controllers do not pass the raw segment; the guard filter (C3) rejects ";" content before them.
- **Matched recommendation:** Yes

## S3: What does a valid Idempotency-Key look like, and what happens to an empty or oversized one?

- **Type:** Spec gap
- **Choice:** D: final: /v2 requires a UUID key; the unversioned POSTs reject any key
- **My reasoning:** Owner OD-4
- **Rejected:**
  - A: Blank = absent; 1-255 printable ASCII, else 400. Drawback noted in research: A client that sends an empty key believing it is protected gets no protection; a retry applies twice..
  - B: Require a UUID, else 400. Superseded by OD-4: absent no longer means "no key" on /v2, and the unversioned POSTs reject a present key.
  - C: Accept anything, store sha-256 of the key. Drawback noted in research: Still needs the blank rule, so it does not remove the validation step..
- **Matched recommendation:** Yes

## S4: How do reviewers start app + Postgres with Docker while bootRun starts only Postgres?

- **Type:** Design choice
- **Choice:** E: final: two files, app in compose.override.yaml on host port 8080 (APP_PORT overrides)
- **My reasoning:** Owner instruction: use the ports main uses
- **Rejected:**
  - A: App under profiles: ["app"]. Drawback noted in research: The README command changes. A reviewer who types the usual `docker compose up --build` gets Postgres only and no API, with no error message. That is the worst failure for a take-home..
  - B: Two files: app in compose.override.yaml. Superseded by H13: build v2 published 18080.
  - C: spring.docker.compose.file=compose.dev.yaml. Drawback noted in research: Postgres is defined in two files and can drift (image version, credentials)..
  - D: Drop Boot's Compose support. Drawback noted in research: Walks back half of D8-C..
- **Matched recommendation:** Yes

## S5: How do error responses guarantee a text/plain body when the client's Accept header asks for JSON?

- **Type:** Spec gap
- **Choice:** A: Always set contentType(TEXT_PLAIN) explicitly
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: @ExceptionHandler(produces = "text/plain"). Drawback noted in research: Spring chooses exception handlers by the Accept header. With Accept: application/json no text/plain handler matches, so the exception skips the advice and reaches Boot's /error, which answers JSON. This makes the bug worse..
  - C: Write the response directly with HttpServletResponse. Drawback noted in research: Controller outcomes (R1) are return values; writing them through the servlet response is awkward and fights ResponseEntity in the idempotency replay path (G14)..
  - D: A, plus a text/plain ErrorController for /error. Drawback noted in research: About 45 more minutes for a path that should be rare once the advice has a catch-all (S6)..
- **Matched recommendation:** Yes
- **Refined by:** C1, A38
- **Current rules (after refinement):**
  - Every error response takes its status, Content-Type and body from one helper, TextErrors, that calls .contentType(MediaType.TEXT_PLAIN): the advice and the controller return the helper's ResponseEntity, and a write's outcome goes through OutcomeResponses, which copies it into a StoredResponse, and StoredResponses, which sends that unchanged (A38); the Tomcat error valve, which can't return a ResponseEntity, takes its body from the same helper's textFor and sets text/plain itself (C1). Never rely on content negotiation for error bodies. (refined by C1, A38)
  - Test each error status with Accept: application/json and assert Content-Type text/plain and the exact body.

## S6: What does a 500 look like, and which URLs does the "spec codes only" rule cover?

- **Type:** Spec gap
- **Choice:** E: final: catch-all → 500 text/plain; library paths keep library behaviour; unknown springdoc group is 404
- **My reasoning:** Follow-up 13 and critique M-42.
- **Rejected:**
  - A: Catch-all → 500 "Internal server error" text/plain; scope G10 to /inventory/**. Superseded by follow-up 13 and critique M-42: the code covers /v3/api-docs.yaml/**, an unknown group is 404 with Boot's JSON body, and the query rule is not literally "every path".
  - B: Let Boot's /error handle 500s. Drawback noted in research: Breaks D6 (every error text/plain) and R3's text/plain rule for exactly the case clients handle worst: an outage..
  - C: Catch-all → 500 with an empty text/plain body. Drawback noted in research: A client or reviewer sees a bare 500 with nothing to read; an empty body is harder to tell apart from the empty-body bug in S5..
  - D: 503 for database outages, 500 for the rest. Drawback noted in research: Conflicts with G10-D: 503 is not a listed code and G10 allows only 500 for faults. Priority 3 (spec over convention) says no..
- **Matched recommendation:** Yes

## S7: Where does the one complete add statement (with the G12 overflow guard) and the purchase statement (with RETURNING) get written down?

- **Type:** Design choice
- **Choice:** A: One canonical statement in the D4 rules
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Keep separate rules, add cross-references. Drawback noted in research: The agent must assemble the SQL from two places, which is the failure this finding describes..
  - C: Canonical SQL in one committed file, CLAUDE.md points to it. Drawback noted in research: Until the file exists (no code yet), the agent has nothing to read, so the first version is still written from prose. It needs A's full text anyway for the first build..
- **Matched recommendation:** Yes
- **Refined by:** E1

## S8: Is an Idempotency-Key unique per SKU and endpoint, or across the whole API?

- **Type:** Spec gap
- **Choice:** D: final: on /v2 the key is global: primary key = key
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: Keep (key, operation, skuId); reword README. Drawback noted in research: A client bug that reuses a key on another SKU or endpoint changes stock with no error, which Stripe and the Brandur design both reject..
  - B: Key is global: primary key = key. Superseded by OD-3: scoped to /v2.
  - C: Scope per operation: (key, operation). Drawback noted in research: Matches neither Stripe nor the IETF draft's examples, so it needs its own explanation..
- **Matched recommendation:** Yes

## S9: Should DECISIONS.md show D8's options A and B as part of the chosen option, and should D0 cover the ai/ folder the repo already has?

- **Type:** Design choice
- **Choice:** A: List combined options as included; refine D0's rule
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Leave the export as generated, add a note. Drawback noted in research: The contradiction stays in the body. A reviewer reading D8 alone still sees 'rejected'..
  - C: Fix the board's export, then regenerate. Drawback noted in research: Changes the board, which is itself a committed AI artifact..
- **Matched recommendation:** Yes

## S10: Where are dependency versions pinned, and how are they written in the docs?

- **Type:** Design choice
- **Choice:** D: final: pin once in the catalog, range + "built with" in the docs; ArchUnit gets a catalog line
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Pin once in the build, range + "built with" in the docs. Superseded: ArchUnit needs a catalog entry.
  - B: Ranges only in the docs. Drawback noted in research: A reviewer can't tell from the docs what was built and tested..
  - C: Exact versions everywhere. Drawback noted in research: Every bump means editing four documents; the drift this finding reports comes back..
- **Matched recommendation:** Yes

## S11: Which shipped SQL statements and constraints must have a test against real Postgres, and how is that list kept?

- **Type:** Design choice
- **Choice:** D: final: every native query and constraint has a Testcontainers test, on both versions, plus concurrent adds
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Rule: every native query and constraint has a Testcontainers test, plus concurrent adds. Superseded: the list follows the real schema (no backfill, no MigrationUpgradeTest) and moves the keyed tests to /v2.
  - B: Named test matrix in docs/test-plan.md, referenced from CLAUDE.md. Drawback noted in research: About 0.5h more than A for the table and keeping names in sync (priority 2)..
  - C: Only concurrency tests plus happy paths. Drawback noted in research: Breaks priority 1: the G12 guard, CHECK, COLLATE "C" order, Link header and idempotency replay/expiry ship without ever being executed by a test..
- **Matched recommendation:** Yes

## S12: What checks that the built API and the exported openapi.yaml still match the original spec's contract?

- **Type:** Design choice
- **Choice:** E: final: contract test per version, spec-only codes unversioned, conformance test against the spec
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Table-driven MockMvc contract test + annotated springdoc. Superseded by the two-document contract (H12): the conformance test replaces the baseline file.
  - B: Export /v3/api-docs and diff with oasdiff. Drawback noted in research: Checks the documentation, not the responses. A handler that really returns */* or a wrong string still passes..
  - C: Validate every MockMvc response against the original YAML. Drawback noted in research: Uses Jackson 2 (2.21) internally while the app uses Jackson 3; they sit in different packages, but running it on Boot 4.1.1 is unverified..
  - D: Manual review only. Drawback noted in research: Conflicts with priority 3: nothing stops a later change from breaking the contract..
- **Matched recommendation:** Yes

## T1: How is an expired Idempotency-Key reused without running the request twice?

- **Type:** Spec gap
- **Choice:** E: final: on /v2, never reuse an expired key
- **My reasoning:** Owner OD-3 and OD-4. Why an expired key is rejected, not treated as new (critique M-07): Stripe removes keys after 24 hours and treats a reused pruned key as a new request (https://docs.stripe.com/api/idempotent_requests); here a retry of a request whose first response was lost more than 24 hours ago would then run as a fresh write, the double write the key exists to prevent. Refusing is safe: the client gets 400 and decides. The IETF draft (-07, expired) only says the server SHOULD define and publish an expiry policy.
- **Rejected:**
  - A: Take over expired keys inside the claim statement. Treating an expired key as new (as Stripe does after pruning) would let a retry of a request answered more than 24 hours ago run again as a fresh write, the double write the key exists to prevent; refusing is safe and the client decides (critique M-07).
  - B: Delete the expired row, then claim. Drawback noted in research: Two statements whose safety depends on lock ordering; harder to explain.
  - C: Lock the key row first. Drawback noted in research: More code paths (row exists / doesn't exist).
  - D: Never reuse an expired key. Superseded by OD-3: scoped to /v2.
- **Matched recommendation:** Yes

## T2: Your S2 note describes a body field that doesn't exist. Which wording stands?

- **Type:** Design choice
- **Choice:** A: Replace the S2 note with corrected wording
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Keep the note as written. Drawback noted in research: A reviewer reads a plan that contradicts the spec.
- **Matched recommendation:** Yes

## T3: What body text do 404 (unknown path), 405 and 406 responses carry?

- **Type:** Spec gap
- **Choice:** B: Standard reason phrase for any status G6 doesn't cover
- **My reasoning:** Keeps it to a string but handles outliers.
- **Rejected:**
  - A: Three more fixed strings. Drawback noted in research: Any other framework status still has no text.
  - C: Reuse G6 strings. Drawback noted in research: Misleading messages.
  - D: Empty body. Drawback noted in research: Inconsistent with every other error having text.
- **Matched recommendation:** Yes

## T6: The plan is estimated at about 45.5h (35.6h before the ledger rounds) against a 24-hour limit. Where is the cut line?

- **Type:** Design choice
- **Choice:** E: final: superseded by H14 (5-hour budget, ai/final/plan.md)
- **My reasoning:** Owner: prompt (5-hour budget)
- **Rejected:**
  - A: Keep the design, build in order, hard stop. Superseded by H14: the final build has a 5-hour budget and its own PR order.
  - B: Cut paging now. Drawback noted in research: Still well over the limit.
  - C: Cut idempotency now. Drawback noted in research: Loses the retry-safety story; keep it as a README future improvement.
  - D: Cut both now. Drawback noted in research: Two cards to change by hand.
- **Matched recommendation:** Yes

## U1: When an add overflows (G12 400), what happens to its Idempotency-Key?

- **Type:** Spec gap
- **Choice:** C: final: Overflow is an outcome and is stored on /v2
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: Add an Overflow outcome and store it. Superseded: storing is a /v2 behaviour.
  - B: Treat overflow like validation: roll back, store nothing. Drawback noted in research: Needs setRollbackOnly, a second pattern next to R1's return-value rule.
- **Matched recommendation:** Yes

## U2: What do the GET endpoints return for an unsupported Accept header?

- **Type:** Spec gap
- **Choice:** D: final: GET ignores Accept on both versions; POST and PUT with an unacceptable Accept → 400
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Ignore Accept; always answer JSON. Drawback noted in research: Applies to all MVC endpoints; actuator and springdoc need a check (unverified).
  - B: Allow 406 as a documented deviation. Drawback noted in research: A code the spec doesn't list on its own operations.
  - C: GET ignores Accept; POST with an unacceptable Accept → 400. Superseded: /v2 GETs and PUT details join the rule.
- **Matched recommendation:** Yes

## U3: On purchase, which check runs first: the Idempotency-Key format (400) or the skuId pattern (404)?

- **Type:** Spec gap
- **Choice:** C: final: two orders, unversioned (key present → 400) and /v2 (key required)
- **My reasoning:** Owner OD-4
- **Rejected:**
  - A: Key format first (400), then skuId (404). Superseded by OD-4: one order for both POSTs no longer fits, because the two versions treat the key differently.
  - B: skuId first (404), then key format (400). Drawback noted in research: Opposite of G4-C's order.
- **Matched recommendation:** Yes

## V1: Should stock changes also be recorded in an append-only ledger?

- **Type:** Design choice
- **Choice:** C: Ledger only (balance = SUM of deltas)
- **My reasoning:** This will help bring this system into closer alignment with shopify https://shopify.engineering/scaling-inventory-reservations Claim: When payment succeeds, we permanently deduct quantity from the inventory ledger (source of truth). They use a reserve and claim process but we're eschewing reserve for now. Concurrency design history (critique M-10): the ledger-SUM design with SERIALIZABLE transactions and retries was built first (V1-C, W1-B, D4-D); #26 showed 40001 serialization failures on writes to different SKUs; it was replaced by a balance row plus the append-only ledger at READ COMMITTED (E1). The old "about 4h more" cost note is cleared.
- **Rejected:**
  - A: No ledger; list it under Future improvements. Drawback noted in research: No stock history in the build.
  - B: Hybrid: balance row + ledger row in the same statement. Drawback noted in research: About 2h with tests.
- **Matched recommendation:** No
- **Refined by:** W1, E1
- **Current rules (after refinement):**
  - Superseded by E1: stock is sku.quantity, changed by one conditional statement per write at READ COMMITTED; every change still appends an inventory_ledger row in the same transaction, and triggers keep the ledger append-only (E3). (refined by W1, E1)

## V2: With quantities stored as bigint/long, how wide is the request quantity, and what happens to the overflow guard?

- **Type:** Spec gap
- **Choice:** A: Request int, stock bigint/long, keep the guard at the bigint max
- **My reasoning:** Unexpected that a request would reach 2,147,483,647. Such a request would likely come through via a special order and would lead to a inventory adjustment instead of purchase via api request.
- **Rejected:**
  - B: Request long too, keep the guard at the bigint max. Drawback noted in research: Overflow is reachable in two requests, so the guard is load-bearing.
  - C: Request int, stock bigint/long, drop the guard. Drawback noted in research: U1 and the G12 boundary test lose their purpose; a 22003 would be a 500.
  - D: Request int, cap stock at 2^53 − 1 for JavaScript safety. Drawback noted in research: A second, less obvious limit to explain.
- **Matched recommendation:** Yes

## W1: With the ledger as the source of truth, how does a write stay correct under concurrency?

- **Type:** Design choice
- **Choice:** B: SERIALIZABLE transactions with retry (D4 → D)
- **My reasoning:** Concurrency design history (critique M-10): the ledger-SUM design with SERIALIZABLE transactions and retries was built first (V1-C, W1-B, D4-D); #26 showed 40001 serialization failures on writes to different SKUs; it was replaced by a balance row plus the append-only ledger at READ COMMITTED (E1, which supersedes W1, W2, X1, Y2 and Z1).
- **Rejected:**
  - A: Lock the SKU row, then check and insert (D4 → B). Drawback noted in research: Two statements per write.
  - C: Go back to the hybrid (V1 → B). Drawback noted in research: The ledger isn't the source of truth.
- **Matched recommendation:** No
- **Refined by:** W2, E1
- **Current rules (after refinement):**
  - Superseded by E1: stock writes run at READ COMMITTED under the sku row's lock, and nothing retries. (refined by W2, E1)

## W2: How many times does a SERIALIZABLE write retry, and what happens when retries run out?

- **Type:** Design choice
- **Choice:** A: 10 retries with jittered backoff; exhaustion → 500
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Default 3 retries; tests accept some 500s. Drawback noted in research: Weakens the S11 concurrent-add test ("all return Ok").
  - C: Switch W1 to A (row lock, no retries). Drawback noted in research: Hot SKUs still serialize (queue instead of retry).
- **Matched recommendation:** Yes
- **Refined by:** E1
- **Current rules (after refinement):**
  - Superseded by E1: nothing retries; there is no retry policy or backoff. (refined by E1)
  - Superseded by E1: there are no retries to exhaust; a database error during a write rolls it back and returns 500 "Internal server error" (G6). (refined by E1)
  - Concurrency tests use at most 8 threads per SKU. inventory_ledger keeps its index on sku_id (SchemaTest asserts it); no application query sums the ledger (E1). (refined by E1)

## X1: Where do the retry and the SERIALIZABLE transaction boundary sit in the code?

- **Type:** Design choice
- **Choice:** B: @Retryable method runs a TransactionTemplate
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - A: Two beans: @Retryable service calls a @Transactional writer. Drawback noted in research: One more class per feature.
  - C: Both annotations on one method. Drawback noted in research: Behaviour not documented.
- **Matched recommendation:** Yes
- **Refined by:** Z1, E1
- **Current rules (after refinement):**
  - Superseded by E1 and A33: nothing retries. Each stock write runs in one READ COMMITTED transaction (PROPAGATION_REQUIRED): the service's TransactionTemplate opens it without an Idempotency-Key, IdempotencyStore.run with one (A33). Stock-write methods have no @Transactional annotation. (refined by Z1, E1)

## Y1: Where is a POST's Accept header checked, so an unacceptable Accept never changes stock?

- **Type:** Design choice
- **Choice:** C: final: produces = application/json on every write mapping of both versions
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: produces = application/json on both POST mappings. Superseded: the rule names every write mapping, and the guard filter (not an interceptor) rejects q=0.
  - B: Check Accept in a filter before the controller. Drawback noted in research: Re-implements media-type matching (q-values, wildcards) by hand.
- **Matched recommendation:** Yes

## Y2: How does the retry tell a serialization failure (40001/40P01) from other lock failures?

- **Type:** Design choice
- **Choice:** A: Framework 7 predicate = MethodRetryPredicate
- **My reasoning:** Inspect the root cause's SQLState at the annotation so the retry stays declarative at the method level (my proposal was Spring Retry's exceptionExpression). Framework 7's predicate does the same with the @Retryable already chosen in W2 and X1.
- **Rejected:**
  - B: Spring Retry exceptionExpression (SpEL). Spring Retry and Apache Commons Lang would be two new dependencies, and its attributes (maxAttempts, @Backoff) differ from the Framework 7 @Retryable chosen in W2 and X1.
  - C: Check inside the retried method. Drawback noted in research: Retry policy split between the annotation and a catch block.
- **Matched recommendation:** Yes
- **Refined by:** E1
- **Current rules (after refinement):**
  - Superseded by E1: nothing retries, so there is no @Retryable, no retry predicate and no @EnableResilientMethods. (refined by E1)
  - Don't add Spring Retry, Apache Commons Lang or any other retry library; nothing retries (E1). (refined by E1)
  - Test: ArchitectureTest (no main class depends on org.springframework.resilience) keeps retries out (E1, A39). (refined by E1)

## Y3: What goes into the Idempotency-Key request hash?

- **Type:** Spec gap
- **Choice:** C: final: SHA-256 of ("v2", operation, skuId, quantity) after parsing
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: SHA-256 of (operation, skuId, quantity) after parsing. Superseded by H10: the hash now carries the API version, so "the same bytes as before" no longer holds, on purpose.
  - B: SHA-256 of the raw body bytes. Drawback noted in research: Formatting differences and unknown fields turn a legitimate retry into 400.
- **Matched recommendation:** Yes

## Y4: How does a replayed response get its Content-Type?

- **Type:** Design choice
- **Choice:** C: final: store content_type with status and body; /v2 replays a SkuItem
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Store content_type with status and body. Superseded: the table serves /v2 only, and its bodies are SkuItems.
  - B: Derive it from the status. Drawback noted in research: A second place that must agree with the controller.
- **Matched recommendation:** Yes

## Z1: Where do Idempotency-Key handling, input checks and the idempotency transaction sit?

- **Type:** Design choice
- **Choice:** B: Service-layer @Idempotent interceptor
- **My reasoning:** Moving idempotency into a MethodInterceptor (@Idempotent) decouples InventoryController and InventoryService from IdempotencyStore while keeping @Retryable as the outermost wrapper ([Retry, Idempotency, Tx]). Returning WriteResult outcome values instead of throwing exceptions keeps bad requests (400/404) from firing MethodRetryEvent and adding WARN logs from StockWriteFailureLogger. spring-aop (MethodInterceptor + StaticMethodMatcherPointcut) is already on the classpath, avoids adding spring-boot-starter-aspectj, and a static @Role(ROLE_INFRASTRUCTURE) @Bean with ObjectProvider dependencies keeps bean post-processing order deterministic. The isolation check keeps InventoryService agnostic of the key while failing fast if a future caller runs a stock write inside a non-SERIALIZABLE transaction.
- **Rejected:**
  - A: In the controller. Doesn't align with standard idempotency practices; the key should be passed through so the service can handle conversions.
  - C: MVC HandlerInterceptor. Drawback noted in research: Can't share the ledger write's transaction (G14) without an in-progress state and a 409 the spec doesn't list.
  - D: Redis in front. Drawback noted in research: A new dependency and a 409 the spec doesn't list (G10).
- **Matched recommendation:** Yes
- **Refined by:** A33
- **Current rules (after refinement):**
  - Superseded by A33: InventoryService calls IdempotencyStore.run explicitly; there is no spring-aop advice, annotation or pointcut for Idempotency-Key handling. (refined by A33)
  - Superseded by A33 and E1: nothing retries, so there is no proxy order to keep. Writes still return result values, never exceptions, for bad input (A38). (refined by A33)
  - Superseded by A33: stock writes run at READ COMMITTED (PROPAGATION_REQUIRED), and IdempotencyStore.run refuses a caller's transaction at any isolation other than READ COMMITTED or DEFAULT. (refined by A33)
  - Superseded by A33: IdempotencyStoreTest and InventoryServiceWriteChecksTest replace the advice-order, keyed-retry and isolation tests. (refined by A33)

## Z2: How is the inventory feature split between web and domain code?

- **Type:** Design choice
- **Choice:** D: final: domain package + web sub-package, listed from the code (as built)
- **My reasoning:** Class names follow the code.
- **Rejected:**
  - A: One flat inventory package. Drawback noted in research: Web and domain code mix; nothing stops the domain importing HTTP types.
  - B: Domain package + web sub-package. Superseded: the list was stale; it is rewritten from the code.
  - C: final: domain package + web sub-package, listed from the code. Superseded: the class list follows the code (no Page, OpenApiConfiguration).
- **Matched recommendation:** Yes

## Z3: What does GET /inventory return for a query string it can't read unambiguously?

- **Type:** Spec gap
- **Choice:** E: final: 400 "Invalid request" on both lists; every path that reads its query
- **My reasoning:** Critique M-42: reworded.
- **Rejected:**
  - A: Ignore what can't be read, 200. Drawback noted in research: Hand-written query parsing in the controller.
  - A2: Catch and ignore both parameters. Drawback noted in research: One bad unrelated parameter drops a valid limit.
  - B: 400 "Invalid request". Superseded: the repeated-after 400 and its documentation now cover GET /v2/inventory too, and the class names follow the real layout (A37).
  - C: Leave the 500. Drawback noted in research: A client error answers 500, against G10.
  - D: final: 400 "Invalid request" on both lists; repeated after on both. Superseded by critique M-42: "/nope?x=%ZZ" is 404 and "/v3/api-docs?x=%ZZ" is 200, so "every path" was not literally true.
- **Matched recommendation:** Yes

## C1: How are errors that Spring MVC never sees, and errors on library paths, rendered?

- **Type:** Spec gap
- **Choice:** F: final: text/plain valve, %2F passthrough, TRACE, /error and bad chunk framing text/plain, advice declines library paths
- **My reasoning:** Critique M-02 (MAJOR): /error answered 500 with Boot's JSON or Whitelabel HTML, and a bad chunk size answered 400 application/json; both now answer text/plain. M-42: the undecodable-query rule says "every path that reads its query". Follow-up 5: "add", not "create". Reviewer nit: the guard filter, interceptor and Tomcat valve write text/plain;charset=UTF-8 while the advice writes text/plain (same media type, charset parameter differs; S5).
- **Rejected:**
  - A: Text/plain valve, %2F passthrough, TRACE through Spring, advice declines library paths. Superseded: the classes live in inventory.web (A37), and /v2 paths are named.
  - B: Leave Tomcat's HTML pages; document them. Drawback noted in research: Breaks D6/S5 (every error is text/plain) and G11's 404 for GET /inventory/A%2FB.
  - C: Hard-code the Allow lists in the valve for TRACE. Drawback noted in research: A second copy of the routing that drifts from the controller.
  - D: Keep %2F rejected; map it in the valve. Drawback noted in research: Hand-written path parsing in a Tomcat valve.
  - E: final: text/plain valve, %2F passthrough, TRACE through Spring, advice declines library paths (inventory.web). Superseded by critique M-02 (MAJOR): a direct /error request and malformed chunk framing answered Boot's JSON or HTML with 500 or 400; and by M-42 and follow-up 5 (wording).
- **Matched recommendation:** Yes

## C2: What does GET /inventory return when no limit is given?

- **Type:** Spec gap
- **Choice:** F: final: fixed 250 page and after-only Link unversioned; limit, limit Link and skuId schema on /v2 (classes as built)
- **My reasoning:** Class name follows the code.
- **Rejected:**
  - A: Default page of 250 (the R8 maximum), Link for the rest. Superseded by OD-5: the unversioned Link no longer carries limit, and the documented skuId schema moves to /v2.
  - B: Keep every row; document the risk. Drawback noted in research: Any unauthenticated client can exhaust the heap (C-02).
  - C: Stream every row. Drawback noted in research: Response time and database load still grow with the table.
  - D: Default page of 50. Drawback noted in research: More round trips; a second number to document next to R8's 250.
  - E: final: fixed 250 page and after-only Link unversioned; limit, limit Link and skuId schema on /v2. Superseded: the class is OpenApiGroups.
- **Matched recommendation:** Yes

## C3: What does the API do with ";" in the skuId segment and with an Accept that gives JSON q=0?

- **Type:** Spec gap
- **Choice:** D: final: guard filter, driven by route kind, for every route of both versions; first listed range wins
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: Raw decoded segment; q=0 refuses JSON on POST. Superseded by the frozen hardening (PROMPT: the frozen behaviour wins where C3 disagrees): the mechanism is the guard filter, the tie-break is "first listed", and the scope covers every /v2 route.
  - B: Reject any ";" under /inventory/**. Drawback noted in research: Changes routes that work today (/inventory;v=1).
  - C: Document as known edges. Drawback noted in research: A request for one ID changes another SKU's stock.
- **Matched recommendation:** Yes

## E1: Where does stock live, and how does a write stay correct under concurrency without retries?

- **Type:** Design choice
- **Choice:** G: final: balance row, conditional UPDATE at READ COMMITTED; the ledger row comes from the V5 trigger (A14)
- **My reasoning:** A14 (V5): the ledger row comes from the trigger.
- **Rejected:**
  - A: Balance row, conditional UPDATE at READ COMMITTED. Superseded: the code runs the purchase as an UPDATE plus a second SELECT, not one statement.
  - B: Keep SUM, SERIALIZABLE and retries. Drawback noted in research: Writes to different SKUs still fail with 40001 under SSI (#26), and every write sums a ledger that only grows.
  - C: Balance kept by a trigger. Drawback noted in research: The trigger, not the conditional UPDATE, decides and returns the balance, and every write runs a second UPDATE (A14).
  - D: Lock, then check. Drawback noted in research: Two statements and a Java-side check (G7) for what one conditional UPDATE does.
  - E: final: balance row, conditional UPDATE at READ COMMITTED; purchase is an UPDATE plus a read. Superseded by follow-up 8: DESIGN-V2 is now cited with its path.
  - F: final: balance row, conditional UPDATE at READ COMMITTED; purchase is an UPDATE plus a read (DESIGN-V2 path cited). Superseded by A14: the ledger row now comes from the V5 trigger, not StockRepository.
- **Matched recommendation:** Yes

## E2: How does the code run SQL once nothing needs Spring Data JPA?

- **Type:** Design choice
- **Choice:** D: final: JdbcClient only, in three places
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: JdbcClient only. Superseded: the code has three SQL places, and no InventoryApplicationTests exists; an ArchUnit rule holds the boundary.
  - B: JPA for reads, JdbcClient for writes (D3 as is). Drawback noted in research: Hibernate ORM, Spring Data and a ddl-auto check for two primary-key queries, and two data-access styles in one transaction (S1).
  - C: Spring Data JDBC. Drawback noted in research: An aggregate and repository layer around statements that are all hand-written SQL with RETURNING.
- **Matched recommendation:** Yes

## E3: How does an existing v1 database move to the balance row, and how is the ledger kept append-only?

- **Type:** Design choice
- **Choice:** G: final: V1 carries the balance row and triggers; append-only holds against the API; V5 records every balance change (A14)
- **My reasoning:** A14 (V5): the owner limit names the recorded UPDATE.
- **Rejected:**
  - A: V3: backfill from the ledger, then row-level triggers. Superseded: build v2 rewrote V1, so there is no V3 backfill and no MigrationUpgradeTest, and no in-place upgrade from main.
  - B: Backfill version as 0. Drawback noted in research: version would count every change for new SKUs but only later ones for migrated SKUs.
  - C: Statement-level triggers (#63). Drawback noted in research: Rejects every UPDATE on sku, which the balance row needs on every write, and blocks the TRUNCATE that test cleanup uses.
  - D: Privilege separation. Drawback noted in research: The deployment has one database role, so it needs a second role and separate migration credentials first.
  - E: final: V1 carries the balance row and triggers; no backfill, no upgrade from main. Superseded by critique M-36: the limit of the trigger guarantee is now stated.
  - F: final: V1 carries the balance row and triggers; no backfill, no upgrade from main; append-only holds against the API. Superseded by A14: since V5 an UPDATE of sku.quantity is recorded in the ledger.
- **Matched recommendation:** Yes

## A11: Which error does the append-only trigger raise?

- **Type:** Design choice
- **Choice:** A: P0001 from RAISE EXCEPTION
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: 42501 insufficient_privilege. Drawback noted in research: Claims a role separation this deployment doesn't have.
- **Matched recommendation:** Yes

## A14: Is quantity = SUM(quantity_delta) kept by the database or asserted by tests?

- **Type:** Design choice
- **Choice:** E: final: Postgres writes the ledger row from the balance change (V5)
- **My reasoning:** Owner decision (2026-09-29): Postgres keeps the ledger in step with the balance (V5).
- **Rejected:**
  - A: Asserted by tests. Superseded: there is no V3 backfill; the helper runs after every write test.
  - B: A trigger keeps the balance. Drawback noted in research: The trigger, not the conditional UPDATE, decides and returns the balance, and each write runs a second UPDATE.
  - C: A constraint trigger checks the sum at commit. Drawback noted in research: Sums the SKU's whole ledger on every commit, the cost E1 removes.
  - D: final: asserted by tests after every write test. Superseded by the owner's decision: Postgres now writes the ledger row from the balance change (V5); the tests still assert the invariant.
- **Matched recommendation:** Yes

## A18: What does a keyed POST get when its key's row is committed without a stored response?

- **Type:** Spec gap
- **Choice:** C: final: on /v2, 400 "Invalid request" for a row with no stored response
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: IllegalStateException → 500 (main). Drawback noted in research: A 500 suggests a server fault where an operator cleared the row.
  - B: 400 "Invalid request" (v2's A18). Superseded by OD-3: scoped to /v2; the note about main's clean-up no longer applies.
- **Matched recommendation:** Yes

## A33: Where does Idempotency-Key handling sit once nothing needs ordering against a retry?

- **Type:** Design choice
- **Choice:** D: final: explicit IdempotencyStore.run on the keyed /v2 entry points only
- **My reasoning:** Owner OD-3 and OD-4
- **Rejected:**
  - A: Explicit call to IdempotencyStore.run. Superseded by OD-3 and OD-4: the keyless path now belongs to the unversioned API alone, and /v2 cannot run without a key.
  - B: Keep the advice at READ COMMITTED. Drawback noted in research: Keeps a pointcut, arguments read by position and a per-method result strategy that only existed to sit between a retry and a SERIALIZABLE transaction.
  - C: Store the domain outcome (#83). Drawback noted in research: Changes the stored format, so keys stored before the change need converting; it is #83's scope.
- **Matched recommendation:** Yes

## A34: In what order are a POST's body, Idempotency-Key and skuId checked, and where?

- **Type:** Spec gap
- **Choice:** E: final: unversioned order rejects the key; /v2 order requires it; each check once (tests as they are)
- **My reasoning:** Test names follow the code (grep of src/test).
- **Rejected:**
  - A: Body → key format → skuId → claim, each checked once in the service. Superseded by OD-4: the single order for both POSTs is split into an unversioned order and a /v2 order.
  - B: skuId before the key format. Drawback noted in research: A malformed key could get 404 on purchase, so S3's 400 would depend on the SKU.
  - C: One SkuId check per Operation. Drawback noted in research: The domain's SkuId depends on idempotency.Operation, a dependency the layout removes (A37).
  - D: final: unversioned order rejects the key; /v2 order requires it; each check once in the service. Superseded: the test line named IdempotencyHeaderOrderTest and IdempotencyApiIntegrationTest, which no longer exist.
- **Matched recommendation:** Yes

## A37: Where does app-wide HTTP code live, and which types and members are public?

- **Type:** Design choice
- **Choice:** E: final: HTTP code stays in inventory.web (package-private); web/ holds HttpConstants only; classes as built
- **My reasoning:** Class names follow the code.
- **Rejected:**
  - A: App-wide HTTP in web; feature endpoints in inventory.web, all package-private. Superseded: the code keeps the advice, the valve and the container settings in inventory.web; moving them buys nothing, and the rules follow the real layout.
  - B: Keep app-wide HTTP code in inventory.web. Drawback noted in research: Code that serves every path lives in one feature's package, and a second feature would import from it.
  - C: Split TextErrors into generic and feature halves. Drawback noted in research: Two helpers, where S5 requires one.
  - D: final: HTTP code stays in inventory.web (package-private); web/ holds HttpConstants only. Superseded: the class is OpenApiGroups, and RouteKind and RoutedPath exist.
- **Matched recommendation:** Yes

## A38: What does each write return, and how does the controller render it?

- **Type:** Design choice
- **Choice:** E: final: sealed non-generic WriteResult; typed WriteResult<O> and one Page<T> were designed, not built
- **My reasoning:** Follow-ups 6 and 11: the card follows the code.
- **Rejected:**
  - A: Per-operation results; the controller keeps its own switch for unkeyed outcomes. Drawback noted in research: Two mappings of the same outcomes (C-36) that must stay byte-identical by hand.
  - B: Per-operation results; every outcome rendered through OutcomeResponses. Superseded: this text now matches the removal PR that deletes create and merges the page types.
  - C: One wide WriteResult (main). Drawback noted in research: Each controller switch carries branches for another operation's outcomes that can't happen.
  - D: final: per-operation typed results after create is removed; one Page<T>. Superseded by follow-ups 6 and 11: the removal PR kept the sealed non-generic WriteResult, and the typed results and single Page<T> were not built.
- **Matched recommendation:** Yes

## A39: How is the package layout checked on the compiled classes?

- **Type:** Design choice
- **Choice:** D: final: ArchUnit core with the rules that hold on the real layout
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - A: ArchUnit core, plain JUnit tests. Superseded by H15: the 20-rule list named classes and rules the code does not have.
  - B: Source scans only. Drawback noted in research: Source scans can't see visibility, annotations, or a dependency without an import line.
  - C: archunit-junit5. Drawback noted in research: A second test-engine artifact for what plain @Test and rule.check already do.
- **Matched recommendation:** Yes

## H1: How are the spec and the extensions versioned?

- **Type:** Spec gap
- **Choice:** E: final: path prefix; the spec's URLs stay byte for byte; "compatible" defined; removal only by an owner decision
- **My reasoning:** Owner OD-2. Rationale (critique M-08): the spec's URLs stay byte for byte what the assessment fixes, and every extension is additive under /v2. Comparison (critique area 8; vendor wording is close, not letter-exact): Google AIP-185 puts the major version first in the URI path (https://google.aip.dev/185); Shopify puts it in the URL, quarterly, with 12 months' support (https://shopify.dev/docs/api/usage/versioning); GitHub uses the X-GitHub-Api-Version header, date-based, default 2022-11-28 when absent, prior version supported 24 months (https://docs.github.com/en/rest/about-the-rest-api/api-versions); Stripe uses the Stripe-Version header, monthly non-breaking releases and twice-yearly breaking majors (https://docs.stripe.com/api/versioning). This build matches AIP-185 and Shopify on the mechanism and differs from GitHub and Stripe on purpose, because the spec's paths cannot change. Deprecation: no schedule; a /v2 operation goes only by an owner decision. "Compatible" means additive response fields and new optional inputs only.
- **Rejected:**
  - A: Path prefix: spec at /inventory, extensions under /v2. Superseded by critique M-08: same decision, now with the definition of "compatible" and the deprecation line.
  - B: Version in a header (dated versions). A header is invisible in a URL, a curl line and the Swagger UI, and needs a default for callers that send none.
  - C: Version in a query parameter. Same visibility problem as a header, and caches key on the URL.
  - D: Extend the unversioned API in place (build v2's shape). The spec's four operations would carry behaviour the spec does not list (idempotency keys, limit, details).
- **Matched recommendation:** Yes

## H2: Are /v2 stock writes keyed, and is the key required?

- **Type:** Spec gap
- **Choice:** A: Idempotency-Key required on /v2 add and purchase
- **My reasoning:** Owner OD-3, OD-4
- **Rejected:**
  - B: Optional key on /v2 (build v2, G8's old choice A). An optional key makes "retry-safe" depend on the caller remembering to send one, and the /v2 contract is the place to say otherwise.
  - C: Required on both versions. The spec has no key.
- **Matched recommendation:** Yes

## H3: What does an unversioned POST do with an Idempotency-Key?

- **Type:** Spec gap
- **Choice:** A: Reject any present header with 400 and change nothing
- **My reasoning:** Owner OD-4. RFC 9110 §6.3: "Other recipients SHOULD ignore unrecognized header and trailer fields." Counter-argument (critique M-28, decision challenge for OD-4, described not changed): fail loudly rather than silently double-write, since a caller who sends a key believes the write is retry-safe. A generic client that adds the key to every POST gets 400 on a spec endpoint. A client without UUID support gets a key by generating any random UUID v4. The front end's key lives only as long as the tab, so its replay safety lasts while the tab does (FE33, FE34).
- **Rejected:**
  - B: Ignore the header. RFC 9110 §6.3 says recipients SHOULD ignore unrecognized header fields, but ignoring the key lets a caller believe the write is retry-safe when it is not; failing loudly is the choice (critique M-28).
  - C: Honour the header (build v2, G8 choice A). The spec never had the key.
- **Matched recommendation:** Yes

## H4: What is the unversioned list's page size?

- **Type:** Spec gap
- **Choice:** A: Fixed 250, only after; the Link carries only after
- **My reasoning:** Owner OD-5. Accepted risk (critique M-09): a client that ignores Link sees 250 of N SKUs with no truncation signal.
- **Rejected:**
  - B: Keep limit (build v2). Page-size control is an extension, and extensions live under /v2.
  - C: Unbounded (the spec). An unbounded list failed with OutOfMemoryError at 1M SKUs (C-02).
- **Matched recommendation:** Yes

## H5: How does /v2 list?

- **Type:** Spec gap
- **Choice:** A: limit 1–250 (lenient, default 250) and after; Link with both
- **My reasoning:** Owner (Target API)
- **Rejected:**
  - B: Strict limit (400 outside 1–250). It would change build v2's behaviour for no spec reason.
- **Matched recommendation:** Yes

## H6: Where do the spec operations appear under /v2, and where do details go?

- **Type:** Spec gap
- **Choice:** A: /v2 mirrors the spec operations; details are PUT …/details
- **My reasoning:** Owner OD-6
- **Rejected:**
  - B: Keep build v2's create-with-details POST and PUT item (201/409). It mixes stock and details in one request, so a retry could double the stock or overwrite details.
- **Matched recommendation:** Yes

## H7: How does the details PUT create and replace?

- **Type:** Spec gap
- **Choice:** D: final: PUT creates (201) or replaces (200); If-Match and If-None-Match: *; 412; costs recorded
- **My reasoning:** Owner OD-11. Accepted costs (critique M-06, decision challenge: prescribed by the Target API, OD-6 and OD-11): an unconditional PUT to an unknown id creates a permanent phantom SKU (a typo such as ABC-l for ABC-1 lists as {"skuId":"ABC-l","quantity":0}, and a purchase of it answers 400 "Insufficient inventory", not 404) and no API deletes it (G5); the Create page always sends If-None-Match: * so the front end never does this by accident. /v2 lock-in: the leniencies (a bad limit is ignored, no precondition is required to create) cannot be tightened later without /v3. 428 (RFC 6585 §3) was rejected: the approved contract creates unconditionally.
- **Rejected:**
  - A: PUT creates a missing SKU at quantity 0 (201) and replaces an existing SKU's details (200); optional If-Match and If-None-Match: *; 412 when one fails. Superseded by follow-up 2: same decision; the "first test decides" sentence moves from the rule to the assumption.
  - B: 404 on a missing SKU (build v2). Creating then needs a second route, and the create form could not be idempotent by method.
  - C: 409 on an existing SKU. 409 needs a create-only route.
- **Matched recommendation:** Yes

## H8: What is the 412 text?

- **Type:** Spec gap
- **Choice:** A: One fixed text for every 412
- **My reasoning:** Owner (Target API fixes the text) Critique M-12 (service text fixed by the Target API): the 412 text is misleading for If-None-Match: * on an existing SKU, because nothing was read or changed. The service keeps it; the Create page compensates and shows only its own "SKU already exists" sentence for that 412 (FE33, FE34).
- **Rejected:**
  - B: A second text for If-None-Match on an existing SKU. A second text would depart from the Target API's fixed text; the Create page compensates instead (critique M-12).
- **Matched recommendation:** Yes

## H9: What does V4 do to the idempotency CHECKs for 'create' and 201/409?

- **Type:** Design choice
- **Choice:** A: V4 narrows both CHECKs with NOT VALID
- **My reasoning:** Approved at the plan gate (2026-09-29) Premise (critique M-29, decision challenge): defensive. No build v2 database is claimed to exist, and E3 says there is no in-place upgrade; the NOT VALID narrowing protects databases nobody is said to have. It stays because migrations V1-V3 are frozen and dropping V4 would leave the schema wider than the API.
- **Rejected:**
  - B: No DDL: remove Operation.CREATE in Java, leave the CHECKs wide. It leaves the schema saying the API can store what it cannot.
  - C: V4 narrows with validation. It fails on a database that holds an old row.
- **Matched recommendation:** Yes

## H10: Does the request hash include the API version?

- **Type:** Spec gap
- **Choice:** A: Yes: hash input "v2" + operation + skuId + quantity
- **My reasoning:** Approved at the plan gate (2026-09-29) Premise (critique M-29, decision challenge): defensive. No build v2 database is claimed to exist, and E3 says there is no in-place upgrade; the "v2" prefix keeps a key stored by an unversioned request from replaying on /v2. Removing it would change behaviour for a migrated database, so it stays.
- **Rejected:**
  - B: No: Y3's bytes. A key stored by build v2's unversioned POST would replay an InventoryItem body on /v2.
- **Matched recommendation:** Yes

## H11: What drives the guard filter?

- **Type:** Spec gap
- **Choice:** C: final: route-kind guard filter with the body cap counted while read on every write route
- **My reasoning:** Critique M-01 lifts the unversioned chunked exemption: every write route counts a chunked body while it is read.
- **Rejected:**
  - A: Classify each request by route kind and apply every frozen check to every route. Superseded by critique M-01: unversioned chunked bodies are capped like every other write body.
  - B: Keep segment counts and add cases. The next new route can repeat L21.
- **Matched recommendation:** Yes

## H12: How is OpenAPI documented across the two versions?

- **Type:** Design choice
- **Choice:** A: Two springdoc groups, two committed files, plus a conformance test against the spec
- **My reasoning:** Owner (Target API)
- **Rejected:**
  - B: One document (build v2). The spec's document cannot stay the spec's while it also carries the extensions.
- **Matched recommendation:** Yes

## H13: Which host ports do the app and the dev server use?

- **Type:** Design choice
- **Choice:** A: App on host 8080, Vite on 5173 (main's)
- **My reasoning:** Owner instruction: use the ports main uses
- **Rejected:**
  - B: 18080 and 15173 (build v2). The owner asked for main's ports.
- **Matched recommendation:** Yes

## H14: What order and budget does the final build follow?

- **Type:** Design choice
- **Choice:** A: Follow ai/final/plan.md; stop and push what is green at the end of the budget
- **My reasoning:** Owner: prompt (5-hour budget)
- **Rejected:**
  - B: Keep T6 (decision-review order, hour 20). It describes main's plan, not this build.
- **Matched recommendation:** Yes

## H15: Which architecture guards does the final build add?

- **Type:** Design choice
- **Choice:** A: ArchUnit with only the rules that hold on the real layout
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Source scans only. Source scans cannot see visibility, annotations or a dependency with no import line.
  - C: The old A39 rule list. It names classes and rules the code does not have.
- **Matched recommendation:** Yes

## H16: Does the guard filter run before body validation? (M-13)

- **Type:** Spec gap
- **Choice:** A: Freeze it: the guard filter answers before the body is validated
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Validate the body before the filter's skuId check. The filter would need the parsed body, or the check would move into the controllers (the L21 shape).
- **Matched recommendation:** Yes

## A17: What format does the Idempotency-Key have, and does a replay say so?

- **Type:** Spec gap
- **Choice:** A: UUID; no replay header
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Any opaque string. Nothing validates it.
  - C: UUID plus an Idempotent-Replayed response header. It adds a response header the spec never had.
- **Matched recommendation:** Yes

## A19: How large may a write body be?

- **Type:** Spec gap
- **Choice:** C: final: 4096 bytes on both POSTs of both versions (chunked counted while read), 65536 on PUT details
- **My reasoning:** Critique M-01 (MAJOR): the prompt's freeze of build v2's unversioned chunked behaviour is lifted for this one item, and the service PR that implements the cap merges first. Both POSTs of both versions: 4096 bytes; PUT details: 65536; chunked bodies counted while read. The app service in compose.override.yaml has mem_limit 768m (MaxRAMPercentage 75 follows it; a plain docker run has no cap).
- **Rejected:**
  - A: 4 KB on both POSTs, 64 KB on PUT details; over the cap → 400 before parsing. Superseded by critique M-01 (MAJOR, confirmed): an unversioned chunked POST had no cap, and 30 concurrent 19 MB chunked bodies took the app from 367 MiB to 3.16 GiB. The prompt froze that behaviour unless the critique rates it MAJOR or worse; it did.
  - B: No cap. A huge body would be parsed.
- **Matched recommendation:** Yes

## A22: Where do SKU details live?

- **Type:** Design choice
- **Choice:** A: Own table sku_details (PK sku_id, version)
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Columns on sku. A details write and a stock write would contend for one row.
- **Matched recommendation:** Yes

## A25: What format do /v2 errors have?

- **Type:** Spec gap
- **Choice:** A: text/plain fixed texts through the S5 helper
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: RFC 9457 problem details. It adds a second error format, and the spec has none.
- **Matched recommendation:** Yes

## A26: What are the cost and SkuDetails field rules?

- **Type:** Spec gap
- **Choice:** A: Integer minor units with an ISO currency; bounded name, description and images
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Decimal or float amount. Floats and decimals invite rounding errors in money.
- **Matched recommendation:** Yes

## A27: How is a /v2 read served?

- **Type:** Design choice
- **Choice:** C: final: one join, no transaction; ETag is the details version only; no-store; conditional GET ignored
- **My reasoning:** Critique M-05: the /v2 strong ETag is the details version only (Target API). Recorded as a known RFC 9110 deviation; no code change.
- **Rejected:**
  - A: One sku LEFT JOIN sku_details query, no transaction; ETag and no-store; conditional GET ignored. Superseded by critique M-05 (decision challenge, not fixable by us): the ETag deviation is now recorded on the card.
  - B: Serve reads from a cache. It adds a second store with no measured need (A30).
- **Matched recommendation:** Yes

## A30: Does the service use any store besides Postgres?

- **Type:** Design choice
- **Choice:** A: Postgres only
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Postgres plus a Redis stock cache. Two stores to keep consistent, and no benchmark shows the need.
- **Matched recommendation:** Yes

## A35: Does the idempotency row store a domain outcome instead of the HTTP response?

- **Type:** Design choice
- **Choice:** A: Keep the rendered response (deferred #83)
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Store the domain outcome. It changes the stored format and is #83's scope.
- **Matched recommendation:** Yes

## A36: Does the service use a message broker?

- **Type:** Design choice
- **Choice:** A: No broker
- **My reasoning:** Approved at the plan gate (2026-09-29)
- **Rejected:**
  - B: Publish stock events to a broker. A second system, and no consumer exists.
- **Matched recommendation:** Yes

## H17: Which server-side time bounds does the service set?

- **Type:** Design choice
- **Choice:** A: Hikari connection-timeout 3000 ms and Postgres lock_timeout 5 s
- **My reasoning:** Critique M-14 (fix): configuration, one line each.
- **Rejected:**
  - B: No bound (Hikari's 30 s default). The 30 s default lets one stalled lock or a database outage hold requests and then the whole pool (critique M-14).
  - C: A per-request statement_timeout. It does not bound the wait for a pooled connection, and one value must fit the slowest legitimate statement.
- **Matched recommendation:** Yes
