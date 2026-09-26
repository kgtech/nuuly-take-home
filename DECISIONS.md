# DECISIONS

Nuuly inventory API take-home. Generated from the decision board on 2026-09-26.
Each entry records my choice and my reasoning; rejected options list my reason, or the option's main drawback from research when I left it blank.

| ID | Type | Question | Choice | Matched recommendation |
|---|---|---|---|---|
| G1 | Spec gap | Are SKU IDs case-sensitive? | A: Case-sensitive, stored as sent | Yes |
| G11 | Spec gap | What characters, length and sort collation does a skuId get? | A: ASCII allowlist, 1–64 chars, COLLATE "C" | Yes |
| G2 | Spec gap | How wide is quantity: 32-bit or 64-bit? | B: int64 (Java long, Postgres bigint) | No |
| G12 | Spec gap | What happens when an add would push stock past the bigint maximum (9,223,372,036,854,775,807)? | A: Guard in the SQL, no row → 400 | Yes |
| G3 | Spec gap | What status does a malformed or unsupported request get? | A: All client request errors → 400 text/plain | Yes |
| G13 | Spec gap | How strictly is the JSON body parsed? | A: Strict numbers, ignore unknown fields | Yes |
| G4 | Spec gap | On purchase, which wins: 404 (SKU missing) or 400 (bad body)? | C: 400 wins (Spring default) | No |
| G5 | Spec gap | What happens to a SKU that sells down to 0? | A: Keep the row; GET returns 0; listed | Yes |
| G6 | Spec gap | What exact text goes in error bodies? | A: Fixed strings from the spec descriptions | Yes |
| G7 | Spec gap | What concurrency guarantee does purchase make? | A: Database-enforced: never oversell, never lose an add | Yes |
| G8 | Spec gap | Should the POST endpoints accept an Idempotency-Key, and is it required? | A: Optional header on both POSTs | Yes |
| G14 | Spec gap | How does an Idempotency-Key behave on reuse, overlap and expiry? | A: Same transaction, replay, spec codes only | Yes |
| G9 | Spec gap | How is the inventory list ordered and paged? | B: Opt-in keyset paging, bare array, Link header | Yes |
| G10 | Spec gap | Auth, and which HTTP status codes may the API return? | D: No auth; spec codes on the spec's operations, standard HTTP elsewhere | No |
| D0 | Design | Where do AI prompts and artifacts live in the repo? | A: agent-prompts.md + CLAUDE.md + DECISIONS.md | Yes |
| D1 | Design | Java and Spring Boot versions | A: Java 25 + Spring Boot 4.1.1 | Yes |
| D2 | Design | Build tool | A: Gradle wrapper 9.x (Kotlin DSL) | Yes |
| D3 | Design | Data access layer | A: Spring Data JPA (Hibernate 7.x), native queries for writes | No |
| D4 | Design | How do add and purchase stay correct under concurrency? | D: SERIALIZABLE isolation + retry | No |
| D5 | Design | How is the schema created and migrated? | A: Flyway migrations + ddl-auto=validate | Yes |
| D6 | Design | How are errors turned into text/plain responses? | A: One @RestControllerAdvice returning text/plain | Yes |
| D7 | Design | Spec-first (generated) or code-first (springdoc)? | A: Code-first + springdoc 3.1.x | Yes |
| D8 | Design | How does a reviewer run it? | C: Both | Yes |
| D9 | Design | What does the test suite run against? | A: Testcontainers Postgres + a concurrency test | Yes |
| D10 | Design | Package layout and health endpoints | A: Feature packages, package-private, actuator health | Yes |
| R1 | Spec gap | When a request fails, what does its Idempotency-Key remember? | B: Store business outcomes as values (Stripe model) | Yes |
| R2 | Spec gap | Two requests arrive at the same moment with the same Idempotency-Key. What happens? | A: INSERT … ON CONFLICT DO NOTHING, then re-read and replay | Yes |
| R3 | Spec gap | Does "every client error → 400" apply to the GET endpoints? | A: 400 on the POSTs only; framework codes outside the contract | Yes |
| R4 | Spec gap | What does GET /inventory do with a bad limit or after? | A: Lenient, always 200 | Yes |
| R5 | Spec gap | G4's reasoning argues the opposite of its choice. Which one stands? | A: Keep G4-C; replace the note | Yes |
| R6 | Design | Gradle build script: Kotlin DSL or Groovy DSL? | A: Kotlin DSL (build.gradle.kts) | Yes |
| R7 | Spec gap | Confirm the skuId pattern | A: Alphanumeric first, 1–64: ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$ | Yes |
| R8 | Spec gap | Confirm the maximum page size for limit | B: 1–250 (Shopify) | Yes |
| R9 | Spec gap | Confirm how and when Idempotency-Keys expire | B: 24h, expire on read (no deletes) | Yes |
| S1 | Design | How does the code run a write statement that returns a row (RETURNING), given that @Modifying queries cannot return rows? | B: Repository fragment using JdbcClient for writes | No |
| S2 | Spec gap | Where is the skuId format checked, so that GET and purchase return 404 (not 400) for an ID that fails the pattern? | C: Plain check in the controller before the lookup | Yes |
| S3 | Spec gap | What does a valid Idempotency-Key look like, and what happens to an empty or oversized one? | B: Require a UUID, else 400 | No |
| S4 | Design | How do reviewers start app + Postgres with Docker while bootRun starts only Postgres? | B: Two files: app in compose.override.yaml | Yes |
| S5 | Spec gap | How do error responses guarantee a text/plain body when the client's Accept header asks for JSON? | A: Always set contentType(TEXT_PLAIN) explicitly | Yes |
| S6 | Spec gap | What does a 500 look like, and which URLs does the "spec codes only" rule cover? | A: Catch-all → 500 "Internal server error" text/plain; scope G10 to /inventory/** | Yes |
| S7 | Design | Where does the one complete add statement (with the G12 overflow guard) and the purchase statement (with RETURNING) get written down? | A: One canonical statement in the D4 rules | Yes |
| S8 | Spec gap | Is an Idempotency-Key unique per SKU and endpoint, or across the whole API? | B: Key is global: primary key = key | Yes |
| S9 | Design | Should DECISIONS.md show D8's options A and B as part of the chosen option, and should D0 cover the ai/ folder the repo already has? | A: List combined options as included; refine D0's rule | Yes |
| S10 | Design | Where are dependency versions pinned, and how are they written in the docs? | A: Pin once in the build, range + "built with" in the docs | Yes |
| S11 | Design | Which shipped SQL statements and constraints must have a test against real Postgres, and how is that list kept? | A: Rule: every native query and constraint has a Testcontainers test, plus concurrent adds | Yes |
| S12 | Design | What checks that the built API and the exported openapi.yaml still match the original spec's contract? | A: Table-driven MockMvc contract test + annotated springdoc | Yes |
| T1 | Spec gap | How is an expired Idempotency-Key reused without running the request twice? | D: Never reuse an expired key | No |
| T2 | Design | Your S2 note describes a body field that doesn't exist. Which wording stands? | A: Replace the S2 note with corrected wording | Yes |
| T3 | Spec gap | What body text do 404 (unknown path), 405 and 406 responses carry? | B: Standard reason phrase for any status G6 doesn't cover | Yes |
| T6 | Design | The plan is estimated at about 45.5h (35.6h before the ledger rounds) against a 24-hour limit. Where is the cut line? | A: Keep the design, build in order, hard stop | Yes |
| U1 | Spec gap | When an add overflows (G12 400), what happens to its Idempotency-Key? | A: Add an Overflow outcome and store it | Yes |
| U2 | Spec gap | What do the GET endpoints return for an unsupported Accept header? | C: GET ignores Accept; POST with an unacceptable Accept → 400 | No |
| U3 | Spec gap | On purchase, which check runs first: the Idempotency-Key format (400) or the skuId pattern (404)? | A: Key format first (400), then skuId (404) | Yes |
| V1 | Design | Should stock changes also be recorded in an append-only ledger? | C: Ledger only (balance = SUM of deltas) | No |
| V2 | Spec gap | With quantities stored as bigint/long, how wide is the request quantity, and what happens to the overflow guard? | A: Request int, stock bigint/long, keep the guard at the bigint max | Yes |
| W1 | Design | With the ledger as the source of truth, how does a write stay correct under concurrency? | B: SERIALIZABLE transactions with retry (D4 → D) | No |
| W2 | Design | How many times does a SERIALIZABLE write retry, and what happens when retries run out? | A: 10 retries with jittered backoff; exhaustion → 500 | Yes |
| X1 | Design | Where do the retry and the SERIALIZABLE transaction boundary sit in the code? | B: @Retryable method runs a TransactionTemplate | Yes |
| Y1 | Design | Where is a POST's Accept header checked, so an unacceptable Accept never changes stock? | A: produces = application/json on both POST mappings | Yes |
| Y2 | Design | How does the retry tell a serialization failure (40001/40P01) from other lock failures? | A: Framework 7 predicate = MethodRetryPredicate | Yes |
| Y3 | Spec gap | What goes into the Idempotency-Key request hash? | A: SHA-256 of (operation, skuId, quantity) after parsing | Yes |
| Y4 | Design | How does a replayed response get its Content-Type? | A: Store content_type with status and body | Yes |
| Z1 | Design | Where do Idempotency-Key handling, input checks and the idempotency transaction sit? | B: Service-layer @Idempotent interceptor | Yes |
| Z2 | Design | How is the inventory feature split between web and domain code? | B: Domain package + web sub-package | Yes |
| Z3 | Spec gap | What does GET /inventory return for a query string it can't read unambiguously? | B: 400 "Invalid request" | No |
| C1 | Spec gap | How are errors that Spring MVC never sees, and errors on library paths, rendered? | A: Text/plain valve, %2F passthrough, TRACE through Spring, advice declines library paths | Yes |

## G1: Are SKU IDs case-sensitive?

- **Type:** Spec gap
- **Choice:** A: Case-sensitive, stored as sent
- **My reasoning:** Shopify uses case-sensitive SKUs, and the examples `widget` and `CW-XYCS-BM-01` indicate case sensitivity should be preserved for this project as well.
- **Rejected:**
  - B: Case-insensitive, normalized to upper case. Drawback noted in research: Responses return a different skuId than the client sent.
- **Matched recommendation:** Yes

## G11: What characters, length and sort collation does a skuId get?

- **Type:** Spec gap
- **Choice:** A: ASCII allowlist, 1–64 chars, COLLATE "C"
- **My reasoning:** Cover the design spec and keep Java and Postgres in alignment.
- **Rejected:**
  - B: Any printable string up to 255, no slash, COLLATE "C". Drawback noted in research: URL-encoding cases (spaces, %2F, Unicode) need tests.
  - C: Any non-blank string, database default collation. Drawback noted in research: Sort order depends on the database's locale (en_US puts a before B; C puts B first).
- **Matched recommendation:** Yes
- **Refined by:** R7, S2, W1, Z1
- **Current rules (after refinement):**
  - Validate skuId against ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$. Check it in the service with a precompiled Pattern (SkuId.isValid), never with @Pattern on the @PathVariable; controllers pass the raw path value. POST create → 400 "Invalid request"; GET and purchase → 404 "SKU not found" without touching the database or the idempotency table (GET has no 400 in the spec). @Valid body validation runs first, so a bad body wins with 400 (G4). (refined by R7, S2, Z1)
  - Column: sku.sku_id varchar(64) COLLATE "C" PRIMARY KEY; inventory_ledger.sku_id references it with the same type and collation (W1). (refined by W1)

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
- **Refined by:** S7, V2, W1
- **Current rules (after refinement):**
  - Adds that would exceed 9223372036854775807 are rejected by the SUM check in the add's INSERT … WHERE (W1). No row returned → 400 "Invalid request". (refined by S7, V2, W1)

## G3: What status does a malformed or unsupported request get?

- **Type:** Spec gap
- **Choice:** A: All client request errors → 400 text/plain
- **My reasoning:** We should return 400 as that is the contract.
- **Rejected:**
  - B: Spec'd cases → 400; Spring defaults elsewhere. Drawback noted in research: Returns codes the spec doesn't list.
- **Matched recommendation:** Yes
- **Refined by:** R3
- **Current rules (after refinement):**
  - On the two POST operations, every client request error returns 400 text/plain, including malformed JSON, a missing body, and a wrong or missing Content-Type (instead of 415). (refined by R3)

## G13: How strictly is the JSON body parsed?

- **Type:** Spec gap
- **Choice:** A: Strict numbers, ignore unknown fields
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Strict numbers and reject unknown fields. Drawback noted in research: The schema doesn't set additionalProperties: false, so this is stricter than the contract.
  - C: Jackson defaults. Drawback noted in research: May accept "10" and truncate 1.5 (unverified).
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
- **Choice:** A: Keep the row; GET returns 0; listed
- **My reasoning:** The SKU should still exist; it returns quantity 0.
- **Rejected:**
  - B: Keep the row; hide from the list. Drawback noted in research: GET and list disagree.
  - C: Delete the row at 0. Drawback noted in research: A purchase can turn a SKU into a 404.
- **Matched recommendation:** Yes
- **Refined by:** W1
- **Current rules (after refinement):**
  - Never delete sku rows or ledger rows; a SKU at 0 keeps its sku row (W1). (refined by W1)

## G6: What exact text goes in error bodies?

- **Type:** Spec gap
- **Choice:** A: Fixed strings from the spec descriptions
- **My reasoning:** Use the description as the exact message strings. Inventory errors should not show inventory counts.
- **Rejected:**
  - B: Fixed prefix plus field detail. Drawback noted in research: More strings to test.
  - C: Free text, including counts. Drawback noted in research: Leaks stock levels, which you ruled out.
- **Matched recommendation:** Yes
- **Refined by:** S6, T3
- **Current rules (after refinement):**
  - Error bodies are exactly one of: "SKU not found", "Insufficient inventory", "Invalid request", or "Internal server error" (500 only). Any other error status uses its standard reason phrase (e.g. "Method Not Allowed"). Never include stock counts. (refined by S6, T3)

## G7: What concurrency guarantee does purchase make?

- **Type:** Spec gap
- **Choice:** A: Database-enforced: never oversell, never lose an add
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: In-JVM lock per SKU. Drawback noted in research: Breaks as soon as a second instance runs.
  - C: No explicit guarantee. Drawback noted in research: Oversells under load.
- **Matched recommendation:** Yes
- **Refined by:** W1
- **Current rules (after refinement):**
  - Stock correctness is enforced by Postgres in one transaction, never by a Java-side check alone. There is no balance column to CHECK; SERIALIZABLE plus retries is the guard (W1). (refined by W1)

## G8: Should the POST endpoints accept an Idempotency-Key, and is it required?

- **Type:** Spec gap
- **Choice:** A: Optional header on both POSTs
- **My reasoning:** Add idempotency key and accept that it changes the header. Generate a new OpenAPI yaml for reference and use Swagger.
- **Rejected:**
  - B: Required header on both POSTs. Drawback noted in research: Every request built from the original spec gets 400.
  - C: Optional header on purchase only. Drawback noted in research: Retried adds still double-count.
  - D: No idempotency. Drawback noted in research: Double-counting on retries.
- **Matched recommendation:** Yes

## G14: How does an Idempotency-Key behave on reuse, overlap and expiry?

- **Type:** Spec gap
- **Choice:** A: Same transaction, replay, spec codes only
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: IETF draft codes (409 / 422). Drawback noted in research: Adds status codes the spec doesn't list.
  - C: Replay successes only. Drawback noted in research: Two stores of truth for the same key over time.
- **Matched recommendation:** Yes
- **Refined by:** R9, S8, T1
- **Current rules (after refinement):**
  - Write the idempotency row in the same transaction as the stock change, keyed by the key alone, storing operation, skuId and a request hash. (refined by S8)
  - Same key + same body → replay stored status and body. Different operation, skuId or body → 400 "Invalid request". A key older than 24h is rejected with 400 "Invalid request" (T1); rows are never purged. (refined by R9, S8, T1)

## G9: How is the inventory list ordered and paged?

- **Type:** Spec gap
- **Choice:** B: Opt-in keyset paging, bare array, Link header
- **My reasoning:** Sort by skuId with pagination. Shopify REST, BigCommerce, commercetools and Salesforce OCAPI page by default (20 to 50 items); Adobe Commerce returns everything unless a page size is given. Inventory APIs (Shopify, Square, Amazon SP-API, Walmart, BigCommerce) all paginate, mostly with cursors. Google AIP-158 and the Azure API guidelines require pagination from the start and call adding it later a breaking change. This spec promises "all SKUs" in a bare array, so a default page size would break the contract.
- **Rejected:**
  - A: Sorted by skuId, no paging. Drawback noted in research: Unbounded response.
  - C: Opt-in offset paging (page, size). Drawback noted in research: OFFSET cost grows; rows shift between pages when stock is added.
  - D: Paged by default (e.g. 50). Drawback noted in research: A client that sends no params no longer gets all SKUs, which breaks the contract.
- **Matched recommendation:** Yes
- **Refined by:** R8
- **Current rules (after refinement):**
  - Optional `limit` (1–250) and `after` (last skuId) query params. Without them return every row. (refined by R8)
  - Keyset query: WHERE sku_id > :after ORDER BY sku_id LIMIT :limit + 1. If the extra row exists, add Link: <…>; rel="next".

## G10: Auth, and which HTTP status codes may the API return?

- **Type:** Spec gap
- **Choice:** D: No auth; spec codes on the spec's operations, standard HTTP elsewhere
- **My reasoning:** The project asks for ease of access. Since this is not a production system we will not implement an auth layer. Keep response codes simple since we return plain text and any issue can be explained via the fixed text response.
- **Rejected:**
  - A: No auth; only spec status codes. Drawback noted in research: Not production-safe (documented).
  - B: No auth; framework defaults. Drawback noted in research: Codes outside the contract.
  - C: Static API key header. Drawback noted in research: Adds setup for reviewers.
- **Matched recommendation:** No
- **Refined by:** S6, U2, Z3, C1
- **Current rules (after refinement):**
  - No authentication.
  - The spec's /inventory/** operations return only the status codes the spec lists for them (GET /inventory also answers 400 "Invalid request" for a query string that can't be decoded or repeats after, Z3; a request Tomcat rejects before routing, such as a malformed percent-escape in the path, answers 400 "Invalid request" on any operation, C1; 500 only for server faults, body "Internal server error"). Other requests under /inventory/** keep standard HTTP codes (404/405; GET ignores Accept, POST answers 400; see U2) with text/plain bodies. Unknown paths follow the same standard-code rule. /actuator/** and the springdoc paths (/v3/api-docs, /v3/api-docs.yaml, /v3/api-docs/**, /swagger-ui.html, /swagger-ui/**) are outside this rule and keep their library behaviour (health 503 when DOWN, the Swagger UI redirect, Spring Boot's JSON error body), except that a request Tomcat rejects before routing is text/plain (C1). (refined by S6, U2, Z3, C1)

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
- **Refined by:** S10
- **Current rules (after refinement):**
  - Java 25 toolchain, Spring Boot 4.1.x (built with 4.1.1; set in gradle/libs.versions.toml) (Spring Framework 7, Jackson 3 under tools.jackson, Hibernate 7.x (version from the Spring Boot BOM; 7.4.5 with Boot 4.1.1)). Use spring-boot-starter-webmvc, not -web. (refined by S10)

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
- **Refined by:** S1, W1, X1, Z1
- **Current rules (after refinement):**
  - Spring Data JPA for reads (balances come from native or projection queries that cast SUM(quantity_delta)::bigint, because SUM(bigint) returns numeric); atomic writes are native SQL run through JdbcClient in a repository fragment, inside the attempt's SERIALIZABLE transaction (X1). No @Modifying. (refined by S1, W1, X1, Z1)

## D4: How do add and purchase stay correct under concurrency?

- **Type:** Design choice
- **Choice:** D: SERIALIZABLE isolation + retry
- **My reasoning:** This is the initial approach. A production system could have a cart service and even a reservation service that holds inventory for a set short period of time. Similar to purchasing ibventory from Ticketmaster the inventory is reserved for x minutes then released if not purchased.
- **Rejected:**
  - A: Atomic conditional UPDATE + ON CONFLICT upsert. Drawback noted in research: 0 rows is ambiguous: one extra SELECT to choose 404 vs 400.
  - B: Pessimistic row lock. Drawback noted in research: Two concurrent creates of a new SKU still race (needs ON CONFLICT or retry).
  - C: Optimistic @Version + retry. Drawback noted in research: Hot SKUs retry a lot.
- **Matched recommendation:** No
- **Refined by:** S1, S7, V2, W1
- **Current rules (after refinement):**
  - Run stock writes at SERIALIZABLE and retry on PessimisticLockingFailureException with root SQLState 40001/40P01 (see W2). (refined by W1)

## D5: How is the schema created and migrated?

- **Type:** Design choice
- **Choice:** A: Flyway migrations + ddl-auto=validate
- **My reasoning:** I’m familiar with flyway and any db changes over the development of the project it will be tracked
- **Rejected:**
  - B: Hibernate ddl-auto=update. Drawback noted in research: Tests never run reviewed DDL.
  - C: schema.sql via spring.sql.init. Drawback noted in research: No versioning.
  - D: Liquibase. Drawback noted in research: More ceremony than needed.
- **Matched recommendation:** Yes
- **Refined by:** W1
- **Current rules (after refinement):**
  - Schema changes only through Flyway migrations in src/main/resources/db/migration; spring.jpa.hibernate.ddl-auto=validate.
  - Migrations declare the sku table, the inventory_ledger table (id bigint identity, sku_id, quantity_delta bigint NOT NULL CHECK (quantity_delta <> 0), reason text NOT NULL CHECK (reason IN ('add','purchase')), created_at; index on sku_id) and the sku_id collation from G11 (W1). (refined by W1)

## D6: How are errors turned into text/plain responses?

- **Type:** Design choice
- **Choice:** A: One @RestControllerAdvice returning text/plain
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Handle errors in each controller. Drawback noted in research: Framework exceptions (parse, media type) still escape.
  - C: ProblemDetail JSON (RFC 9457). Drawback noted in research: Spec requires text/plain.
- **Matched recommendation:** Yes
- **Refined by:** R1, S5
- **Current rules (after refinement):**
  - One @RestControllerAdvice maps every thrown error (parsing, validation, framework) to ResponseEntity<String> built by one helper that sets .contentType(MediaType.TEXT_PLAIN) explicitly (never left to content negotiation); the controller's 404/400 outcome responses use the same helper. Keep ProblemDetail off. (refined by R1, S5)

## D7: Spec-first (generated) or code-first (springdoc)?

- **Type:** Design choice
- **Choice:** A: Code-first + springdoc 3.1.x
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Spec-first with openapi-generator ≥ 7.20. Drawback noted in research: Boot 4 support is new; one bug's fix is unconfirmed.
  - C: Code-first + contract check against the original spec. Drawback noted in research: Another hour.
- **Matched recommendation:** Yes
- **Refined by:** S10, S12
- **Current rules (after refinement):**
  - Controllers are hand-written. springdoc-openapi 3.1.x (set in gradle/libs.versions.toml) serves /v3/api-docs and Swagger UI; a test writes /v3/api-docs to openapi.yaml, asserts every error response is text/plain, and the file is committed. (refined by S10, S12)

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
- **Choice:** A: Testcontainers Postgres + a concurrency test
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: H2 in PostgreSQL mode. Drawback noted in research: Doesn't run the same SQL (unverified which parts fail).
  - C: Mock the repository. Drawback noted in research: Never runs SQL; can't prove concurrency.
- **Matched recommendation:** Yes
- **Refined by:** S10, W2
- **Current rules (after refinement):**
  - Integration and repository tests run against Postgres via Testcontainers 2.x (version from the Spring Boot BOM) @ServiceConnection, with the Flyway migrations. (refined by S10)
  - Include a concurrent purchase test: N ≤ 8 threads, stock M < N, assert exactly M succeed and final quantity is 0. (refined by W2)

## D10: Package layout and health endpoints

- **Type:** Design choice
- **Choice:** A: Feature packages, package-private, actuator health
- **My reasoning:** Include health endpoints. Feature-based packages: classes can remain package-private and only entry points need to be public; limited blast radius (delete the feature folder to remove a feature); resembles a microservice-style approach and domain-driven design.
- **Rejected:**
  - B: Layered packages + actuator health. Drawback noted in research: Everything must be public.
  - C: Feature packages, no actuator. Drawback noted in research: Compose has no health check to wait on.
- **Matched recommendation:** Yes
- **Refined by:** Z2
- **Current rules (after refinement):**
  - Package by feature (inventory/, idempotency/); inventory/ is split into domain and web sub-packages (Z2). Classes are package-private unless another package uses them: domain contract types the web layer consumes are public, while persistence internals (SkuRepository, JPA entities, repository fragments) stay package-private in the domain package. (refined by Z2)
  - Expose /actuator/health (liveness and readiness).

## R1: When a request fails, what does its Idempotency-Key remember?

- **Type:** Spec gap
- **Choice:** B: Store business outcomes as values (Stripe model)
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - A: Replay successes only. Drawback noted in research: Changes G14 from A to C, and the README wording.
  - C: Keep throwing; record failures in a separate transaction. Drawback noted in research: Two transactions per failure.
- **Matched recommendation:** Yes
- **Refined by:** U1, Z1
- **Current rules (after refinement):**
  - Inside the stock transaction, return an outcome value (Ok / NotFound / Insufficient / Overflow); never throw for business results. Rejections of a malformed skuId or Idempotency-Key, and of a reused key, are outcome values too. The controller maps outcomes to 200/404/400. (refined by U1, Z1)
  - Store 200, 404 "SKU not found", 400 "Insufficient inventory" and the overflow 400 "Invalid request" against the key. Validation 400s are not stored. (refined by U1)

## R2: Two requests arrive at the same moment with the same Idempotency-Key. What happens?

- **Type:** Spec gap
- **Choice:** A: INSERT … ON CONFLICT DO NOTHING, then re-read and replay
- **My reasoning:** This highlights the need for a redis cache to catch idempotency keys before the query is run. Considering our time constraint I think we should flag this as a better long term solution in the readme. Redis would catch the second use of the same key and prevent the attempted update.
- **Rejected:**
  - B: Catch the unique violation and retry in a new transaction. Drawback noted in research: Exception-driven; needs SQLState checks.
  - C: Reject the second request with 400. Drawback noted in research: A legitimate retry gets an error.
  - D: Advisory lock on the key first. Drawback noted in research: Extra round trip on every keyed request.
- **Matched recommendation:** Yes
- **Refined by:** S8, W1
- **Current rules (after refinement):**
  - Claim the key with INSERT … ON CONFLICT DO NOTHING RETURNING. No row → SELECT the stored response and replay it; under SERIALIZABLE a concurrent claim may instead raise 40001, and the W1 retry replays (different operation, skuId or request hash → 400 "Invalid request"). (refined by S8, W1)

## R3: Does "every client error → 400" apply to the GET endpoints?

- **Type:** Spec gap
- **Choice:** A: 400 on the POSTs only; framework codes outside the contract
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Only spec codes, everywhere. Drawback noted in research: DELETE on an existing SKU returns 404, which is misleading.
  - C: Keep G3 as written: 400 everywhere. Drawback noted in research: GET returns a code the spec doesn't list.
- **Matched recommendation:** Yes
- **Refined by:** U2, C1
- **Current rules (after refinement):**
  - Map client errors to 400 only on the two POST operations; a request Tomcat rejects before routing is 400 "Invalid request" on any method (C1). Outside the spec's operations keep Spring's 404/405 (GET ignores Accept; POST answers 400; see U2), with text/plain bodies. (refined by U2, C1)

## R4: What does GET /inventory do with a bad limit or after?

- **Type:** Spec gap
- **Choice:** A: Lenient, always 200
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Coerce large values, 400 for nonsense. Drawback noted in research: Adds a 400 to an operation that lists only 200.
  - C: Strict 400 for any bad value. Drawback noted in research: Adds a 400 the spec doesn't list.
- **Matched recommendation:** Yes
- **Refined by:** Z3
- **Current rules (after refinement):**
  - GET /inventory returns 400 "Invalid request" only when its query string can't be decoded or repeats after (Z3). Non-positive or non-numeric limit → ignored. limit above the max → the max. after is compared as a plain string and never validated; after alone returns every row after it. (refined by Z3)

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
- **Choice:** A: Alphanumeric first, 1–64: ^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$
- **My reasoning:** Square recommends starting with an alphanumeric character and not including punctuation.  SKUs: Each SKU should be unique for accurate inventory tracking. Avoid duplicate SKUs, even if it’s a variation of the same product. Do not use special characters or punctuation; only use numbers and letters from the alphabet. Keep it concise and logical. The first few letters should represent the highest category of importance depending on your business (for example, brand, make, then model). Always starting with a letter can help employees easily identify product categories. Maintain consistency in your SKU format across all products to streamline your inventory management. This consistency makes it easier for employees to understand and manage the SKUs. Periodically review and update your SKU system to ensure it remains relevant and efficient, especially as your product assortments evolve. https://squareup.com/us/en/the-bottom-line/operating-your-business/stock-keeping-unit
- **Rejected:**
  - B: Keep ^[A-Za-z0-9._-]{1,64}$ and reject "." and ".." explicitly. Drawback noted in research: Special cases in code.
  - C: Alphanumeric first, up to 255. Drawback noted in research: Longer keys for no stated need.
- **Matched recommendation:** Yes

## R8: Confirm the maximum page size for limit

- **Type:** Spec gap
- **Choice:** B: 1–250 (Shopify)
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - A: 1–1000. Drawback noted in research: Larger than the commerce APIs above.
  - C: 1–100 (GitHub, Stripe). Drawback noted in research: More requests for large inventories.
- **Matched recommendation:** Yes

## R9: Confirm how and when Idempotency-Keys expire

- **Type:** Spec gap
- **Choice:** B: 24h, expire on read (no deletes)
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - A: 24h, purge on write. Drawback noted in research: Extra statement on every keyed write.
  - C: 24h, scheduled cleanup. Drawback noted in research: A scheduler to configure and test.
  - D: No expiry. Drawback noted in research: A client can never reuse a key.
- **Matched recommendation:** Yes
- **Refined by:** T1
- **Current rules (after refinement):**
  - Keys expire after 24h: a key older than 24h is rejected with 400 (T1). No purge job. (refined by T1)

## S1: How does the code run a write statement that returns a row (RETURNING), given that @Modifying queries cannot return rows?

- **Type:** Design choice
- **Choice:** B: Repository fragment using JdbcClient for writes
- **My reasoning:** The cleanest way to handle ⁠RETURNING⁠ clauses in Spring Boot is to bypass Hibernate's query execution for these specific atomic methods and use ⁠JdbcClient⁠ (or ⁠JdbcTemplate⁠) within a custom repository implementation. This gives direct control over the result set mapping while still participating in the current Spring transaction. If we take the @query approach without ⁠@Modifying⁠, we lose ⁠clearAutomatically = true⁠ and ⁠flushAutomatically = true⁠. If we use this hack, we must enforce a strict transactional boundary rule: Never read the entity into the Hibernate L1 cache before this write happens in the same transaction. Otherwise, subsequent reads will hit the stale cached entity instead of the database.
- **Rejected:**
  - A: Plain native @Query without @Modifying. Drawback noted in research: Relies on undocumented behaviour: Spring Data closed #2270 as invalid, and nothing states that Hibernate 7.1 runs DML through getResultList. Needs a spike test before building on it..
  - C: EntityManager.createNativeQuery in a custom fragment. Drawback noted in research: Same unconfirmed Hibernate behaviour as option A (DML through getResultList). JPA only forbids getResultList for JPQL UPDATE/DELETE and says nothing about native DML..
  - D: Drop RETURNING: @Modifying row count, then SELECT. Drawback noted in research: Two round trips on every successful add and purchase..
- **Matched recommendation:** No
- **Refined by:** X1, Z1
- **Current rules (after refinement):**
  - Atomic writes live in a repository fragment (e.g. InventoryWrites + InventoryWritesImpl) and run through JdbcClient. Reads use Spring Data JPA.
  - JdbcClient writes (ledger and idempotency) run only inside the attempt's SERIALIZABLE transaction that X1 defines (JpaTransactionManager shares the connection). Never mix a JPA entity change and a JdbcClient write in one transaction. (refined by X1, Z1)

## S2: Where is the skuId format checked, so that GET and purchase return 404 (not 400) for an ID that fails the pattern?

- **Type:** Spec gap
- **Choice:** C: Plain check in the controller before the lookup
- **My reasoning:** Instead of fighting the framework's validation interceptors, scope the validation to fit the desired HTTP responses. skuId comes from the path on every endpoint; the request body only has quantity. Never put @Pattern on the @PathVariable. The controller checks the pattern itself: POST create → 400 "Invalid request"; GET and purchase → 404 "SKU not found" through the shared error helper (S5), before any database or idempotency work.
- **Rejected:**
  - A: No format check on GET and purchase; lookup returns 404. Drawback noted in research: A bad ID costs a database round trip..
  - B: @Pattern everywhere, map HandlerMethodValidationException per endpoint. Drawback noted in research: Body errors on these methods move from MethodArgumentNotValidException to HandlerMethodValidationException. The handler must inspect getParameterValidationResults() and the method (getMethod()) to keep G4-C. Easy to get wrong..
- **Matched recommendation:** Yes
- **Refined by:** Z1
- **Current rules (after refinement):**
  - Never put constraint annotations on @PathVariable parameters (they switch on method validation, which answers 400).
  - Controllers pass the raw path skuId to the service. The service checks it with SkuId.isValid() before any database or idempotency work and returns an outcome value: create → InvalidRequest (400 "Invalid request"); purchase → NotFound and GET → empty (404 "SKU not found"). With an Idempotency-Key, the @Idempotent interceptor runs the same check (IdempotentResults.beforeClaim) before it opens a transaction, and the result is not stored. (refined by Z1)

## S3: What does a valid Idempotency-Key look like, and what happens to an empty or oversized one?

- **Type:** Spec gap
- **Choice:** B: Require a UUID, else 400
- **My reasoning:** The provided spec does not account for idempotency and considering we don’t want duplicate transactions we need to account for preventing the accidental double purchase or accidental double inventory issues. If someone added inventory and then the request is processed twice because the system didn’t realize it was the same request we could sell stock that wouldn’t be available. This would cause user confusion and abrasion leading to a mistrust of the service and could dampen the reputation.
- **Rejected:**
  - A: Blank = absent; 1-255 printable ASCII, else 400. Drawback noted in research: A client that sends an empty key believing it is protected gets no protection; a retry applies twice..
  - C: Accept anything, store sha-256 of the key. Drawback noted in research: Still needs the blank rule, so it does not remove the validation step..
- **Matched recommendation:** No
- **Refined by:** Z1
- **Current rules (after refinement):**
  - Idempotency-Key: absent means no key (G8). The controller passes the raw header to the service; the @Idempotent interceptor checks it. A present key, including an empty one, must match ^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$; otherwise → 400 "Invalid request", returned as an outcome value before any database work. This 400 is not stored (R1). (refined by Z1)
  - Column: idempotency key uuid.

## S4: How do reviewers start app + Postgres with Docker while bootRun starts only Postgres?

- **Type:** Design choice
- **Choice:** B: Two files: app in compose.override.yaml
- **My reasoning:** This approach trades Spring Boot configuration for reliance on native Docker Compose file resolution.  The Reviewer Win: Reviewers run standard ⁠docker compose up --build⁠. No profiles to remember, no custom flags. It works exactly as expected by default.  The Developer Trap: The primary friction point is for developers who prefer to spin up the database manually via CLI before running their IDE. If they type ⁠docker compose up⁠, they will accidentally start the app container and encounter an ⁠8080⁠ port conflict when they launch the local app. They must rely on ⁠bootRun⁠ to start the DB automatically, or explicitly run ⁠docker compose up postgres⁠ to isolate the database. As long as the team relies on Spring Boot's auto-start for the database rather than manual CLI commands, this override pattern provides the cleanest out-of-the-box experience.
- **Rejected:**
  - A: App under profiles: ["app"]. Drawback noted in research: The README command changes. A reviewer who types the usual `docker compose up --build` gets Postgres only and no API, with no error message. That is the worst failure for a take-home..
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
- **Refined by:** C1
- **Current rules (after refinement):**
  - Build every error response (advice and controller) with one helper that calls .contentType(MediaType.TEXT_PLAIN); the Tomcat error valve, which can't return a ResponseEntity, takes its body from the same helper's textFor and sets text/plain itself (C1). Never rely on content negotiation for error bodies. (refined by C1)
  - Test each error status with Accept: application/json and assert Content-Type text/plain and the exact body.

## S6: What does a 500 look like, and which URLs does the "spec codes only" rule cover?

- **Type:** Spec gap
- **Choice:** A: Catch-all → 500 "Internal server error" text/plain; scope G10 to /inventory/**
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Let Boot's /error handle 500s. Drawback noted in research: Breaks D6 (every error text/plain) and R3's text/plain rule for exactly the case clients handle worst: an outage..
  - C: Catch-all → 500 with an empty text/plain body. Drawback noted in research: A client or reviewer sees a bare 500 with nothing to read; an empty body is harder to tell apart from the empty-body bug in S5..
  - D: 503 for database outages, 500 for the rest. Drawback noted in research: Conflicts with G10-D: 503 is not a listed code and G10 allows only 500 for faults. Priority 3 (spec over convention) says no..
- **Matched recommendation:** Yes
- **Refined by:** T3, C1
- **Current rules (after refinement):**
  - The advice has one @Hidden @ExceptionHandler(Exception.class): log the stack trace at ERROR and return 500 text/plain "Internal server error". If the exception is an ErrorResponse, use its status and G6's fixed text, or the status's standard reason phrase when G6 has none. (refined by T3)
  - G10 and the text/plain error contract cover /inventory/** and every other path except /actuator/** and the springdoc paths (/v3/api-docs, /v3/api-docs.yaml, /v3/api-docs/**, /swagger-ui.html, /swagger-ui/**). On those library paths InventoryErrorAdvice rethrows the exception, so their errors keep library behaviour (Spring Boot's /error JSON, an empty 406, health 503 when DOWN, the Swagger UI redirect). A request Tomcat rejects before routing is text/plain on every path (C1). (refined by C1)

## S7: Where does the one complete add statement (with the G12 overflow guard) and the purchase statement (with RETURNING) get written down?

- **Type:** Design choice
- **Choice:** A: One canonical statement in the D4 rules
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Keep separate rules, add cross-references. Drawback noted in research: The agent must assemble the SQL from two places, which is the failure this finding describes..
  - C: Canonical SQL in one committed file, CLAUDE.md points to it. Drawback noted in research: Until the file exists (no code yet), the agent has nothing to read, so the first version is still written from prose. It needs A's full text anyway for the first build..
- **Matched recommendation:** Yes

## S8: Is an Idempotency-Key unique per SKU and endpoint, or across the whole API?

- **Type:** Spec gap
- **Choice:** B: Key is global: primary key = key
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - A: Keep (key, operation, skuId); reword README. Drawback noted in research: A client bug that reuses a key on another SKU or endpoint changes stock with no error, which Stripe and the Brandur design both reject..
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
- **Choice:** A: Pin once in the build, range + "built with" in the docs
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Ranges only in the docs. Drawback noted in research: A reviewer can't tell from the docs what was built and tested..
  - C: Exact versions everywhere. Drawback noted in research: Every bump means editing four documents; the drift this finding reports comes back..
- **Matched recommendation:** Yes

## S11: Which shipped SQL statements and constraints must have a test against real Postgres, and how is that list kept?

- **Type:** Design choice
- **Choice:** A: Rule: every native query and constraint has a Testcontainers test, plus concurrent adds
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Named test matrix in docs/test-plan.md, referenced from CLAUDE.md. Drawback noted in research: About 0.5h more than A for the table and keeping names in sync (priority 2)..
  - C: Only concurrency tests plus happy paths. Drawback noted in research: Breaks priority 1: the G12 guard, CHECK, COLLATE "C" order, Link header and idempotency replay/expiry ship without ever being executed by a test..
- **Matched recommendation:** Yes
- **Refined by:** T1, V2, W1, W2
- **Current rules (after refinement):**
  - Every native SQL statement and every migration constraint has at least one Testcontainers test that executes it against Postgres. Minimum set: create path (sku row + first ledger row); add path; G12 guard at 9223372036854775807 (accepted) and one above (400, no ledger row inserted; seed one large ledger row directly, since the API can't reach the limit); SERIALIZABLE purchase (W1) (200 with remaining, 400 insufficient, 404 missing); SUM(quantity_delta) never negative after concurrent purchases; COLLATE "C" order; keyset query and Link header (last page has no Link); idempotency claim, replay, different body → 400, key older than 24h rejected with 400; two concurrent requests with the same fresh key produce one stock change. (refined by T1, V2, W1)
  - Include a concurrent add test: N ≤ 8 threads add 1 to one new SKU through the service; assert all return Ok and the final quantity is N. (refined by W2)
  - Concurrency tests are not @Transactional; clean tables in @BeforeEach.

## S12: What checks that the built API and the exported openapi.yaml still match the original spec's contract?

- **Type:** Design choice
- **Choice:** A: Table-driven MockMvc contract test + annotated springdoc
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Export /v3/api-docs and diff with oasdiff. Drawback noted in research: Checks the documentation, not the responses. A handler that really returns */* or a wrong string still passes..
  - C: Validate every MockMvc response against the original YAML. Drawback noted in research: Uses Jackson 2 (2.21) internally while the app uses Jackson 3; they sit in different packages, but running it on Boot 4.1.1 is unverified..
  - D: Manual review only. Drawback noted in research: Conflicts with priority 3: nothing stops a later change from breaking the contract..
- **Matched recommendation:** Yes
- **Refined by:** Z3
- **Current rules (after refinement):**
  - A parameterized MockMvc test has one row per response in the original spec and asserts status, Content-Type and exact body.
  - Set springdoc.override-with-generic-response=false. Each controller method declares @ApiResponse for exactly the spec's codes, plus GET /inventory's 400 (Z3); error responses use mediaType "text/plain". (refined by Z3)

## T1: How is an expired Idempotency-Key reused without running the request twice?

- **Type:** Spec gap
- **Choice:** D: Never reuse an expired key
- **My reasoning:** For the take home I would not support the reuse because redis or a cache would be better to suppprt idempotency due to its speed.
- **Rejected:**
  - A: Take over expired keys inside the claim statement. Drawback noted in research: The claim SQL gets longer.
  - B: Delete the expired row, then claim. Drawback noted in research: Two statements whose safety depends on lock ordering; harder to explain.
  - C: Lock the key row first. Drawback noted in research: More code paths (row exists / doesn't exist).
- **Matched recommendation:** No

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
- **Choice:** A: Keep the design, build in order, hard stop
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Cut paging now. Drawback noted in research: Still well over the limit.
  - C: Cut idempotency now. Drawback noted in research: Loses the retry-safety story; keep it as a README future improvement.
  - D: Cut both now. Drawback noted in research: Two cards to change by hand.
- **Matched recommendation:** Yes

## U1: When an add overflows (G12 400), what happens to its Idempotency-Key?

- **Type:** Spec gap
- **Choice:** A: Add an Overflow outcome and store it
- **My reasoning:** Overflow is effectively unreachable in this take-home (Integer requests, bigint stock, V2-A), but the guard and the stored Overflow result keep the behaviour defined. A real application following Stripe's model would store the result the same way.
- **Rejected:**
  - B: Treat overflow like validation: roll back, store nothing. Drawback noted in research: Needs setRollbackOnly, a second pattern next to R1's return-value rule.
- **Matched recommendation:** Yes

## U2: What do the GET endpoints return for an unsupported Accept header?

- **Type:** Spec gap
- **Choice:** C: GET ignores Accept; POST with an unacceptable Accept → 400
- **My reasoning:** Only for GET endpoints. If this occurred on a POST or Delete endpoint I would flag it as a 400 and reject as an invalid request.
- **Rejected:**
  - A: Ignore Accept; always answer JSON. Drawback noted in research: Applies to all MVC endpoints; actuator and springdoc need a check (unverified).
  - B: Allow 406 as a documented deviation. Drawback noted in research: A code the spec doesn't list on its own operations.
- **Matched recommendation:** No

## U3: On purchase, which check runs first: the Idempotency-Key format (400) or the skuId pattern (404)?

- **Type:** Spec gap
- **Choice:** A: Key format first (400), then skuId (404)
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: skuId first (404), then key format (400). Drawback noted in research: Opposite of G4-C's order.
- **Matched recommendation:** Yes
- **Refined by:** Z1
- **Current rules (after refinement):**
  - Order on both POSTs: body validation (@Valid, controller) → Idempotency-Key format (@Idempotent interceptor, 400) → skuId pattern (service check; run by the interceptor before the claim when a key is present; create 400, purchase 404) → claim and stock write. (refined by Z1)

## V1: Should stock changes also be recorded in an append-only ledger?

- **Type:** Design choice
- **Choice:** C: Ledger only (balance = SUM of deltas)
- **My reasoning:** This will help bring this system into closer alignment with shopify https://shopify.engineering/scaling-inventory-reservations Claim: When payment succeeds, we permanently deduct quantity from the inventory ledger (source of truth). They use a reserve and claim process but we're eschewing reserve for now.
- **Rejected:**
  - A: No ledger; list it under Future improvements. Drawback noted in research: No stock history in the build.
  - B: Hybrid: balance row + ledger row in the same statement. Drawback noted in research: About 2h with tests.
- **Matched recommendation:** No
- **Refined by:** W1
- **Current rules (after refinement):**
  - Stock is the SUM of inventory_ledger.quantity_delta per SKU. Every write checks the SUM and inserts the delta in one SERIALIZABLE transaction: Add: INSERT INTO sku (sku_id) VALUES (:id) ON CONFLICT DO NOTHING; then INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) SELECT :id, :q, 'add' WHERE (SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_ledger WHERE sku_id = :id) <= 9223372036854775807 - :q RETURNING ((SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_ledger WHERE sku_id = :id) + :q)::bigint; no row → Overflow. Purchase: INSERT INTO inventory_ledger (sku_id, quantity_delta, reason) SELECT :id, -:q, 'purchase' WHERE EXISTS (SELECT 1 FROM sku WHERE sku_id = :id) AND (SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_ledger WHERE sku_id = :id) >= :q RETURNING ((SELECT COALESCE(SUM(quantity_delta),0) FROM inventory_ledger WHERE sku_id = :id) - :q)::bigint; no row → SELECT the sku row in the same transaction: missing → NotFound, otherwise Insufficient. The RETURNING subquery doesn't see the row being inserted, so the balance is the old SUM ± :q. The ledger is append-only: never UPDATE or DELETE inventory_ledger rows (W1). (refined by W1)
- **Unresolved conflicts at export:** About 4h more, plus a new concurrency design.

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
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - A: Lock the SKU row, then check and insert (D4 → B). Drawback noted in research: Two statements per write.
  - C: Go back to the hybrid (V1 → B). Drawback noted in research: The ledger isn't the source of truth.
- **Matched recommendation:** No
- **Refined by:** W2
- **Current rules (after refinement):**
  - Stock writes run at SERIALIZABLE and retry on SQLSTATE 40001 or 40P01 as W2 specifies; X1 sets where the retry and the transaction boundary sit. (refined by W2)

## W2: How many times does a SERIALIZABLE write retry, and what happens when retries run out?

- **Type:** Design choice
- **Choice:** A: 10 retries with jittered backoff; exhaustion → 500
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - B: Default 3 retries; tests accept some 500s. Drawback noted in research: Weakens the S11 concurrent-add test ("all return Ok").
  - C: Switch W1 to A (row lock, no retries). Drawback noted in research: Hot SKUs still serialize (queue instead of retry).
- **Matched recommendation:** Yes

## X1: Where do the retry and the SERIALIZABLE transaction boundary sit in the code?

- **Type:** Design choice
- **Choice:** B: @Retryable method runs a TransactionTemplate
- **My reasoning:** _No notes recorded._
- **Rejected:**
  - A: Two beans: @Retryable service calls a @Transactional writer. Drawback noted in research: One more class per feature.
  - C: Both annotations on one method. Drawback noted in research: Behaviour not documented.
- **Matched recommendation:** Yes
- **Refined by:** Z1
- **Current rules (after refinement):**
  - @Retryable sits on the service's stock-write method and wraps everything beneath it; each attempt runs in one new SERIALIZABLE transaction. Without an Idempotency-Key, the method's TransactionTemplate (ISOLATION_SERIALIZABLE, PROPAGATION_REQUIRED) starts it. With a key, the @Idempotent interceptor, which runs inside the retry, starts it (TransactionTemplate, ISOLATION_SERIALIZABLE, PROPAGATION_REQUIRES_NEW) and runs the claim, the stock write and the stored response in it; the method's TransactionTemplate joins. Stock-write methods have no @Transactional annotation. (refined by Z1)

## Y1: Where is a POST's Accept header checked, so an unacceptable Accept never changes stock?

- **Type:** Design choice
- **Choice:** A: produces = application/json on both POST mappings
- **My reasoning:** Accepted the fix: declare produces = application/json on both POST mappings so the Accept check happens before the service runs.
- **Rejected:**
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

## Y3: What goes into the Idempotency-Key request hash?

- **Type:** Spec gap
- **Choice:** A: SHA-256 of (operation, skuId, quantity) after parsing
- **My reasoning:** Hashing (operation, skuId, quantity) is acceptable.
- **Rejected:**
  - B: SHA-256 of the raw body bytes. Drawback noted in research: Formatting differences and unknown fields turn a legitimate retry into 400.
- **Matched recommendation:** Yes

## Y4: How does a replayed response get its Content-Type?

- **Type:** Design choice
- **Choice:** A: Store content_type with status and body
- **My reasoning:** Storing the content type is standard procedure.
- **Rejected:**
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

## Z2: How is the inventory feature split between web and domain code?

- **Type:** Design choice
- **Choice:** B: Domain package + web sub-package
- **My reasoning:** In Java, sub-packages are separate packages with no shared package-private visibility, so once inventory is split into domain and web, the domain contract types the web layer consumes (InventoryService, WriteResult, StockOutcome) must be public, while internal domain/persistence mechanics (SkuRepository, JPA entities, repository fragments) stay package-private. Refining D10 documents that public is expected at the web → domain boundary so reviews don't flag it. Splitting web (HTTP controllers, request/response DTOs, TextErrors, OutcomeResponses JSON rendering) from domain (stock business logic, SkuId validation, ledger writes) keeps a one-way dependency so the domain can't import Spring MVC or HTTP transport types. Recording it as a new card that refines D10 follows the Z1 pattern and preserves the history of why the package structure changed after review.
- **Rejected:**
  - A: One flat inventory package. Drawback noted in research: Web and domain code mix; nothing stops the domain importing HTTP types.
- **Matched recommendation:** Yes

## Z3: What does GET /inventory return for a query string it can't read unambiguously?

- **Type:** Spec gap
- **Choice:** B: 400 "Invalid request"
- **My reasoning:** The OpenAPI spec needs to be updated to document the 400.
- **Rejected:**
  - A: Ignore what can't be read, 200. Drawback noted in research: Hand-written query parsing in the controller.
  - A2: Catch and ignore both parameters. Drawback noted in research: One bad unrelated parameter drops a valid limit.
  - C: Leave the 500. Drawback noted in research: A client error answers 500, against G10.
- **Matched recommendation:** No

## C1: How are errors that Spring MVC never sees, and errors on library paths, rendered?

- **Type:** Spec gap
- **Choice:** A: Text/plain valve, %2F passthrough, TRACE through Spring, advice declines library paths
- **My reasoning:** Approved.
- **Rejected:**
  - B: Leave Tomcat's HTML pages; document them. Drawback noted in research: Breaks D6/S5 (every error is text/plain) and G11's 404 for GET /inventory/A%2FB.
  - C: Hard-code the Allow lists in the valve for TRACE. Drawback noted in research: A second copy of the routing that drifts from the controller.
  - D: Keep %2F rejected; map it in the valve. Drawback noted in research: Hand-written path parsing in a Tomcat valve.
- **Matched recommendation:** Yes
