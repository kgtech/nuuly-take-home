# Decision review: inconsistencies found

Four review agents ran in parallel on 2026-09-24, each with its own focus: spec contract, internal consistency, stack feasibility, and priorities/testability. I merged duplicate findings and checked the two biggest claims against the source code and docs. Each finding below was turned into a decision or is still open.

## Resolution status (2026-09-24)

| Finding | Resolved by | Choice |
|---|---|---|
| A3 idempotency rollback and concurrency | R1, R2 | R1-B store business outcomes as values; R2-A ON CONFLICT DO NOTHING, then re-read |
| B1 400 on GET endpoints | R3, G10 | R3-A 400 on POSTs only; G10 switched to D |
| B2 bad paging parameters | R4 | R4-A lenient, always 200 |
| B4 `.` and `..` skuIds | R7 | R7-A must start with a letter or digit |
| C1 G4 note vs choice | R5 | R5-A keep C, note rewritten |
| C6 details from option text | R6, R7, R8, R9 | Kotlin DSL; `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`; limit 1–250; 24h, expire on read |
| A1 @Modifying vs RETURNING | S1 | Open on the board |
| A2 skuId validation on the path | S2 | Open on the board |
| A4 Idempotency-Key format | S3 | Open on the board |
| A5 Compose clash with bootRun | S4 | Open on the board |
| A6 text/plain error bodies | S5 | Open on the board |
| B3 500 body and G10 scope | S6 | Open on the board |
| C2 canonical SQL in D4 | S7 | Open on the board |
| C3 Idempotency-Key scope | S8 | Open on the board |
| C4 rejected lists for D0/D8 | S9 | Open on the board |
| C5 version wording | S10 | Open on the board |
| D1 tests for the shipped SQL | S11 | Open on the board |
| D2 contract check | S12 | Open on the board |

Status: **Confirmed** means the conflicting text or source was checked. **Plausible** means the reasoning holds but hasn't been proven by a test.

## A. Rules that would produce broken code

| # | Decisions | Problem | Suggested resolution | Status |
|---|---|---|---|---|
| A1 | D3 × D4, G12 | D3 says atomic writes use `@Modifying`. Spring Data only allows `void`, `int` or `long` returns for `@Modifying` and calls `executeUpdate()`, so the add's `RETURNING` row and G12's "no row returned" signal can't come back. Purchase also has to return the remaining quantity. | Use `@Modifying` only for statements that return no rows. `RETURNING` statements use a plain native `@Query` returning `Optional<Integer>`, called from a `@Transactional` service. Add `RETURNING quantity` to the purchase UPDATE. | Confirmed ([JpaQueryExecution](https://github.com/spring-projects/spring-data-jpa/blob/main/spring-data-jpa/src/main/java/org/springframework/data/jpa/repository/query/JpaQueryExecution.java), [#1708](https://github.com/spring-projects/spring-data-jpa/issues/1708)) |
| A2 | G11 × G4 × spec | A `@Pattern` on the path skuId triggers `HandlerMethodValidationException` (400) before the controller runs. That breaks G11's "GET and purchase → 404" and puts a 400 on GET, which the spec doesn't list. When both the skuId and the body are bad, G11 says 404 and G4-C says 400. | Don't validate the path skuId's format on GET or purchase. An ID that fails the pattern can't exist, so the lookup returns 404. Keep format validation for POST create only, and state the order: body errors (400) before a missing SKU. | Confirmed ([Spring validation docs](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html)) |
| A3 | G14 × D6 × G10 | The key row is written in the same transaction as the stock change. Errors are thrown exceptions, so a 400 or 404 rolls back the key row and only 200s are ever replayed, which is the rejected option G14-C. Two concurrent requests with the same key: the second fails with a unique violation (23505), which becomes a 500. | Decide what gets stored: 200s only, or 200s plus business 400/404. Insert the key with `ON CONFLICT DO NOTHING`, then re-read and replay. | Plausible |
| A4 | G8 × G14 | The key format isn't defined. A blank key would be stored, and an oversized key fails in Postgres with 22001 → 500. | Treat a blank key as absent. A key over 255 characters or outside printable ASCII → 400 "Invalid request". | Plausible |
| A5 | D8 | Boot's Docker Compose support runs `docker compose up` on the whole file. If `compose.yaml` defines the app, `./gradlew bootRun` also starts the app container, and both want port 8080. The `org.springframework.boot.ignore` label only skips the connection; the container still starts. | Put the app service under `profiles: ["app"]`. Reviewers run `docker compose --profile app up`. | Confirmed ([Boot dev services docs](https://docs.spring.io/spring-boot/reference/features/dev-services.html)) |
| A6 | D6 × G3 | When the client's `Accept` excludes text/plain, Spring can send the error status with no body. | Always build error responses with `.contentType(MediaType.TEXT_PLAIN)`. Test a 404 with `Accept: application/json`. | Plausible ([#23421](https://github.com/spring-projects/spring-framework/issues/23421)) |

## B. Rules that contradict the spec contract

| # | Decisions | Problem | Suggested resolution | Status |
|---|---|---|---|---|
| B1 | G3 × G10 × spec | "Every client request error → 400" also covers the GET endpoints, and neither lists a 400. An unknown route (`/inventory/a/b`) would become 400 instead of 404. 405 and 406 aren't decided. | Limit G3 to the two POSTs. Unknown routes stay 404. Decide 405/406 (map to 404 on GETs, or accept them as a documented deviation). | Confirmed |
| B2 | G9 × G10 × G11 | `GET /inventory` lists only 200. G9 adds `limit` (1–1000) and `after`, but no rule says what `limit=0`, `limit=abc`, `after=<bad>`, or `after` without `limit` return. | Clamp or ignore bad values and always return 200 (AIP-158 coerces page size down to the maximum), or document 400 as an extension in the exported openapi.yaml. | Confirmed |
| B3 | G10 × D10, D7 | "Only 200, 400, 404" conflicts with actuator health returning 503 and the Swagger redirect returning 302. The body text for a 500 isn't defined. | Scope G10 to `/inventory/**`. Define the 500 body text. | Confirmed |
| B4 | G11 | The regex `^[A-Za-z0-9._-]{1,64}$` accepts `.` and `..`, which path normalization makes unroutable. | Require an alphanumeric first character: `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`. | Plausible |

## C. Documents disagree with each other

| # | Decisions | Problem | Suggested resolution | Status |
|---|---|---|---|---|
| C1 | G4 | The choice is C (400 wins), but the reasoning argues for 404-first and "include both errors" (option D, which G6 rules out). All three document reviewers flagged it. | Rewrite the G4 note to justify C. | Confirmed |
| C2 | D4 × G12 | CLAUDE.md's D4 add statement leaves out the G12 overflow guard. An agent following D4 alone would drop it. | Put the full statement (with the guard and `RETURNING`) in one rule. | Confirmed |
| C3 | G14 × README | Keys are scoped by `(key, operation, skuId)`, but the README says reusing a key with a different body returns 400. Under that scope, the same key on another SKU runs as a new request. | Reword the README ("same key on the same endpoint and SKU"), or scope keys by key alone. | Confirmed |
| C4 | D8, D0 | The board export lists options as "rejected" that are actually used: D8-C is "Both", and README links the `ai/` folder that D0-B described. | Edit those two entries by hand. The export template doesn't handle combined options. | Confirmed |
| C5 | D1, D2 | Versions are written differently across documents: Boot "4.1.1" / "4.1.x" / "4.1", and Gradle "9.x" / "9.1 or later". | Use "Spring Boot 4.1.x (built on 4.1.1)" and "Gradle 9.1+" everywhere. | Confirmed |
| C6 | D2, G11, G9, G14, D7, D9 | Some details came from the option text, not from you: Kotlin DSL, the exact regex, `limit` 1–1000, 24h key expiry, springdoc 3.1.x, Testcontainers 2.x. | Confirm each one in your notes, or change it. | Confirmed |

## D. Gaps against your priorities

| # | Priority | Problem | Suggested resolution | Status |
|---|---|---|---|---|
| D1 | 1: tests run the shipped SQL | D9 only requires the concurrent purchase test. Nothing requires tests for the G12 guard, the upsert (create and add paths), idempotency (replay, different body, purge), keyset paging and the Link header, `COLLATE "C"` ordering, the CHECK constraint, or concurrent adds (G7 promises "never lose an add"). | Add a D9 rule: every native query and migration constraint gets at least one Testcontainers test. Add a concurrent-add test. | Confirmed |
| D2 | 3: spec contract | D7-A has no check against the original contract, while G8 and G9 add to it. springdoc may list error bodies as `*/*` instead of text/plain. | Add one test that fetches `/v3/api-docs`, checks the original paths, status codes and media types, and writes the committed openapi.yaml. | Plausible |
| D3 | 2: time | The chosen scope is estimated at about 23h against a 16h budget. You chose to keep everything. | Build order below: the spec-complete version is finished before idempotency and paging. | Noted |

## Resolved unverified claims

- **G13 (Jackson 3 coercion):** Jackson 3 still accepts `"10"` and `1.5` by default. Two properties turn that off: `spring.jackson.mapper.allow-coercion-of-scalars=false` and `spring.jackson.deserialization.accept-float-as-int=false`. Don't set `spring.jackson.use-jackson2-defaults=true`. Use `Integer` in the DTO so a missing field isn't silently 0. (Checked against source code; still write the request test.)
- **D5 (`ddl-auto=validate`):** Hibernate 7.1 validation checks that tables and columns exist and that types match. It doesn't check length or collation, so `varchar(64) COLLATE "C"` passes. Tables with no entity are ignored.
- **D9 (dependency names):**
  - Testcontainers 2.x artifacts are `testcontainers-postgresql` and `testcontainers-junit-jupiter`.
  - The container class is `org.testcontainers.postgresql.PostgreSQLContainer`, and it needs an image name.
  - Spring Boot 4 needs `@AutoConfigureMockMvc` with `@SpringBootTest`, `spring-boot-starter-flyway` plus `flyway-database-postgresql`, and `spring-boot-testcontainers`.

## Suggested build order (full scope kept)

1. Gradle 9.1+ and Boot 4.1 skeleton, Flyway V1 (`sku` and `inventory_ledger` tables, index on `sku_id`), `ddl-auto=validate`, and one Testcontainers context test. The idempotency table waits for step 6. *(Updated in round 8 for the ledger design.)*
2. Repository tests against Postgres: the add and purchase statements in V1's rule (create path, add path, G12 guard with a seeded ledger row, 404 vs 400 on purchase), the `quantity_delta <> 0` CHECK, the index used by the SUM query, the W2 retry wrapper, and `COLLATE "C"` order.
3. The four spec operations plus the error advice. Contract tests for every status code, the exact error strings, text/plain, and the G3/G4/G11/G13/U2/U3 edge cases.
4. Concurrent purchase and concurrent add tests (N ≤ 8 threads per SKU). **The spec is now complete.**
5. compose.yaml (Postgres only) plus compose.override.yaml (the app), Dockerfile and health. Check the README from a clean clone. **The repo is now submittable.**
6. Idempotency (Flyway V2), with A3 and A4 decided and tested.
7. Keyset paging and the Link header, with B2 decided and tested.
8. springdoc export plus the contract test, final README, and the agent-prompts.md entry.

## Round 3: review after round-2 answers (2026-09-24)

Cards T1, T2, T3 and T6 are on the board ("Review round 3"). T4 is folded into T1's test rule. T5 and T7 are still open.

### Logic errors

| # | Decisions | Problem | Example | Suggested fix |
|---|---|---|---|---|
| T1 | R2 × R9-B × S8-B | Expired keys can run twice. R2 claims a key with `ON CONFLICT DO NOTHING`. R9-B says a key older than 24h is "treated as unseen and overwritten". Two concurrent retries of an expired key both get no row back from the claim, both read the expired row, both treat it as new, and both apply the stock change. | Key K last used 25h ago. Two purchases with K arrive together → both deduct stock. | Claim with `INSERT … ON CONFLICT (key) DO UPDATE SET … WHERE idempotency_keys.created_at < now() - interval '24 hours' RETURNING`. The second request waits on the row lock, sees a fresh `created_at`, gets no row, and replays. |
| T2 | S2 note × spec | The S2 note says to put `@Pattern` on "the skuId field inside the @RequestBody DTO" for create. The request body (`InventoryQuantity`) has only `quantity`; skuId is in the path on every endpoint. The chosen option (S2-C, check in the controller) is right, but the note describes something that can't be built. | `POST /inventory/bad!id` with `{"quantity":5}`: there is no body field to validate. | Reword the S2 note: create also checks the path skuId in the controller → 400. |
| T3 | S6-A × R3-A × G6 | No body text is defined for unknown paths (404), 405 or 406. S6's rule says "use its status and the matching fixed text", but G6's four strings don't cover these. | `DELETE /inventory/widget` → 405 with what body? `GET /inventory/a/b` → 404 "SKU not found" would be misleading. | Add fixed texts, e.g. "Not found", "Method not allowed", "Not acceptable", or use the standard reason phrase for any status outside G6. |

### Gaps

| # | Decisions | Problem | Suggested fix |
|---|---|---|---|
| T4 | S11-A × R2 × G8 | The minimum test list has no test for two simultaneous requests with the same key, which is the promise R2 makes in the README ("produce one change"). It also has no test for T1's expired-key case. | Add both to the S11 list. |
| T5 | S9-A × tooling | S9-A's rule says DECISIONS.md is "edited by hand after export", but every regeneration overwrites hand edits. The export now lists combined options as "Included in the choice" by itself (D8 shows A and B as included), so the hand edit isn't needed. | Reword the S9 rule to "the export lists combined options as included", or keep it and stop regenerating DECISIONS.md. |
| T6 | Priority 2 | The estimated build is now 35.6h. That is above the 16h budget and above the 24-hour limit itself. | Choose a cut line. The build order already reaches a submittable, spec-complete version at step 5. |
| T7 | S3-B (minor) | A Postgres `uuid` column treats upper- and lower-case keys as the same value, so `ABC…` and `abc…` are one key. That's reasonable, but it differs from the case-sensitive rule for SKUs. | Note it in the README, or normalize keys to lower case before storing. |

### Fixed in the export tooling (no decision changed)
- The D3/S1 note was shown as a conflict. It was informational and has been removed.
- The README listed the idempotency reuse assumption twice (G14 and S8). S8's text now only refines G14's.
- The G6 README assumption didn't include "Internal server error" (added by S6).
- D8's combined choice listed A and B as rejected. They're now "Included in the choice".
- The README intro said "Spring Boot 4.1". It now says "4.1.x (built with 4.1.1)" to match S10.

### Round 3 resolution (2026-09-24)
| Finding | Card | Choice |
|---|---|---|
| T1 expired-key race | T1 | D: never reuse an expired key (400). The race is gone because both requests get 400. |
| T2 S2 note | T2 | A: note corrected |
| T3 off-contract body text | T3 | B: standard reason phrase |
| T6 scope | T6 | A: build in order, hard stop at hour 20 |
| T4 missing tests | T1 refines S11 | The concurrent fresh-key test was added to the S11 list |
| T5 S9 rule wording | S9 | Fixed: DECISIONS.md is generated, never hand-edited; the export lists combined options as included |
| T7 UUID key case | — | No change: UUIDs are case-insensitive by standard; not documented |

## Round 4: gaps found while writing stories (2026-09-24)

| # | Finding | Card | Status |
|---|---|---|---|
| 1 | Overflow 400 has no idempotency outcome; a claimed key can be left with no stored response | U1 | Open on the board (recommended A: add an Overflow outcome and store it) |
| 2 | An Accept header that excludes JSON gets 406 on the GET operations, which G10-D doesn't allow. R3 wrongly called this "outside the spec's operations" | U2 | Open on the board (recommended A: ignore Accept, always JSON) |
| 3 | On purchase, the key-format check (400) and the skuId check (404) have no defined order | U3 | Open on the board (recommended A: key first, matching G4-C) |
| 4 | Build order and research files missing | — | Not a design gap: both files are in the delivered package (ai/decision-review.md, ai/research-sources.md) |
| 5 | File layout differs from D0/S9 | — | Not a design gap: the delivered package already uses the D0 layout |
| 6 | S2 note said "throw SkuNotFoundException" | — | Fixed: the note now says 404 goes through the shared error helper (S5). Claude wrote the original wording in T2 |
| 7 | D3's choice label predates S1 | — | Fixed: DECISIONS.md now lists "Current rules (after refinement)" under every refined decision |
| 8 | 24h limit is self-imposed | — | Noted: the assessment sets no time limit |
| 9 | No T4/T5 cards | — | Noted: T4 was merged into T1's rule and T5 was the S9 rule fix |

## Round 5: ledger and 64-bit quantities (2026-09-24)

- **G2 changed from A to B:** quantities are now bigint/long. Kenneth's reason: no defensive code for backend calculations.
- **V2 (open):** request width and the overflow guard under bigint. Recommended: A. Request stays Integer, stock is bigint/long, and the guard keeps its place with the bigint maximum. Until V2 is answered, G12, D4, S11 and the README still say 2,147,483,647.
- **V1 (open):** ledger. Recommended: A (Future improvements). If built, B (hybrid, same statement) is the only option that keeps D4's no-oversell guarantee.
- **Claims checked from an external write-up:**
  - Confirmed: ledger ids must be bigint. An int identity failed at 2,147,483,647 in a local Postgres 16 test; Crunchy Data gives the same advice.
  - Not supported:
    - A windowed SUM overflows from lifetime throughput. It returned bigint, and a signed running sum equals the balance.
    - BIGINT to Java int truncates silently. pgjdbc throws "Bad value for type int".
    - Martin Fowler's PofEAA covers key exhaustion. This was misattributed, and the source withdrew it.

## Round 6: answers to U1–U3, V1, V2 (2026-09-24)

- **U1-A:** the note said overflow wasn't handled. Kenneth kept A, and the note was reworded: overflow is effectively unreachable with V2-A, but the guard and the stored result keep the behaviour defined.
- **U2:** the note asked for "GET only; POST → 400". Added U2 option C and set it: a filter makes GET ignore Accept, and POST maps 406 to 400. R3, G10 and the README were updated.
- **U3-A:** no issues.
- **V2-A:** the bigint guard constant now appears in G12, D4, S11 and the README.
- **V1-C (ledger is the source of truth):** this conflicts with D4-A's atomic UPDATE. New card **W1** (open) designs the concurrency:
  - Recommended: A. Lock the SKU row first, then check the SUM and insert in a separate statement (Read Committed snapshots make a single statement unsafe). This moves D4 to B.
  - Choosing W1-A rewrites G7, G12, D4, D5 and S11 for the ledger path.
- **Estimate:** about 43.7h once W1-A is applied. V1-C still carries a priority-2 warning.

## Round 7: W1-B (SERIALIZABLE) checked (2026-09-24)

- **Answers:** Kenneth chose W1-B (SERIALIZABLE + retry), and D4 moved to D.
- **Fixed in the rules** (my omissions; no decision changed):
  - W1-B hadn't updated D5, G12 or S11 for the ledger. D5 still declared `CHECK (quantity >= 0)`.
  - V1's rule still said "per-SKU lock", which describes W1-A.
  - D4's retry exception is now PessimisticLockingFailureException with SQLState 40001/40P01. Spring maps 40001 to CannotAcquireLockException or CannotSerializeTransactionException depending on the translator.
  - R2's rule now notes that under SERIALIZABLE a concurrent key claim may raise 40001 (unverified) and is replayed on retry.
- **New card W2 (open): retry budget and what happens when retries run out.**
  - The problem: Spring's default of 3 retries would fail the S11 test (N concurrent adds to one SKU, all expected to succeed).
  - On a small test table the planner may scan the whole ledger table sequentially, which locks the entire table for SERIALIZABLE and raises aborts across SKUs.
  - Recommended: A. 10 retries with jittered backoff, a 500 when retries run out, an index on sku_id, and at most 8 threads per SKU in tests.
- **Estimate:** about 44.7h. V1-C's priority-2 warning remains.

### Round 7 resolution
- **W2-A:** 10 retries with jittered backoff; when they run out, 500 "Internal server error". The ledger has an index on sku_id, and concurrency tests use at most 8 threads per SKU.
- **All 57 decisions are answered.** The only flag left is V1-C's priority-2 time warning (estimate about 45.5h against a 16h build budget). T6's hour-20 stop applies.
- **Check during the build:** the exact Spring Framework 7 @Retryable attribute names (jitter, multiplier, maxDelay) used in the W2 example.

## Round 8: review of the GitHub stories (2026-09-24)

A second agent reviewed the stories against the decisions (M1–M9). I checked each finding against the board source. All were real; M1 (files in `docs/`) Kenneth had already fixed.

### Fixed in the rules (wording; no choice changed)
| # | Problem | Fix |
|---|---|---|
| M2 | V1's rule gave the write SQL as `INSERT … SELECT … WHERE (…) … RETURNING`. Nothing specified creating the sku row, telling 404 from 400, or computing the returned balance. | V1's rule now holds the full add and purchase statements. The RETURNING subquery doesn't see the inserted row, so it returns the old SUM ± :q. |
| M3 | Build steps 1, 2 and 5 above still described the upsert, the CHECK constraint, the idempotency table in V1 and "app behind a profile" (replaced by S4). | Steps rewritten for the ledger design; the idempotency table moved to step 6. |
| M4 | G5 said "inventory rows", G11 named no table, S11 said "row unchanged", D5 said "bigint delta" and didn't declare `reason`, and nothing said the ledger is append-only. | Reworded. D5 declares `quantity_delta` and `reason`. V1 says the ledger is append-only. |
| M5 (wording) | W1 retried on 40001 only; W2 and D4 say 40001 or 40P01. | W1 now defers to W2. |
| M6 | T6 said 35.6h. | T6 now says about 45.5h. Even with idempotency and paging both cut, the summed estimate is about 34.5h. |
| M7 | `SUM(bigint)` returns `numeric` in Postgres, so a JPA projection gets `BigDecimal`. | D3: every SUM is cast `::bigint`. |
| M8 | D9's concurrent purchase test had no thread cap. | N ≤ 8, matching W2-A. |
| M9 | The API can't reach the bigint limit (about 4 billion maximum-size adds). | S11: seed one large ledger row directly. |
| — | G12's question still said "int32 maximum". | Now says bigint. |

### New card X1 (open): where the retry and the transaction boundary sit
- S1 puts writes "inside a @Transactional service method". W2 needs a new transaction for each retry. A retry inside the failed transaction can't succeed.
- Options: A, two beans (@Retryable service calls a @Transactional writer); B, the @Retryable method runs a TransactionTemplate; C, both annotations on one method (ordering undocumented).
- Conflict check: A and B refine S1 and D3. C adds a priority-1 warning.

### Verified this round
- Spring Framework 7 `@Retryable` attribute names: `maxRetries`, `delay`, `jitter`, `multiplier`, `maxDelay`, `includes`. Total attempts = 1 + `maxRetries`. Needs `@EnableResilientMethods`.

### Issues file
- `ai/github-issues.md` was rewritten to follow the build order above (Compose at story 5). The earlier version put Compose in story 1, which broke T6.

### Round 8 resolution
- **X1-B:** the `@Retryable` service method runs each attempt through a `TransactionTemplate` with `ISOLATION_SERIALIZABLE`. S1 and D3 now name the TransactionTemplate instead of `@Transactional`.
- **Verified:** `JpaTransactionManager` with `HibernateJpaDialect` applies per-transaction isolation levels while `prepareConnection` is `true` (the default).
- **All 58 decisions are answered.** The only flag left is V1-C's priority-2 time warning (about 45.7h estimated against a 16h build budget).

## Round 9: story review follow-ups (2026-09-24)

A third review of the stories against the current rules (after M1–M9 were fixed) found three rule gaps and a missing replay detail. Kenneth answered each one; cards Y1–Y4 are on the board ("Round 9").

| # | Finding | Card | Choice |
|---|---|---|---|
| 1 | U2 maps a POST's unacceptable Accept to 400, but without `produces` on the mapping Spring only raises `HttpMediaTypeNotAcceptableException` while writing the 200 body, after the ledger insert has committed. The client gets 400 and stock changes, and a stored 200 fails the same way on every replay. | Y1 | A: `produces = application/json` on both POST mappings, so the check runs at handler lookup |
| 2 | `@Retryable(includes = PessimisticLockingFailureException.class)` also retries lock timeouts (55P03); W2 limits retries to 40001/40P01, and no rule said where that check lives. | Y2 | A: Framework 7 `predicate = MethodRetryPredicate` that checks the root SQLState |
| 3 | "Request hash" was undefined. Hashing raw bytes makes whitespace or an ignored unknown field (G13) turn a legitimate retry into 400. | Y3 | A: SHA-256 of (operation, skuId, quantity) after parsing |
| 4 | The idempotency table stored status and body but not Content-Type, so a replay couldn't tell JSON from text/plain. | Y4 | A: store `content_type` and replay it unchanged |

### Notes
- **Y2:** Kenneth proposed Spring Retry's `exceptionExpression` (SpEL) with a helper using Apache Commons Lang. That is the separate spring-retry library, with different attribute names (`maxAttempts`, `@Backoff`), and it would add two dependencies. Spring Framework 7's own `@Retryable`, chosen in W2 and X1, has a `predicate` attribute (`Class<? extends MethodRetryPredicate>`, method `shouldRetry(Method, Throwable)`) that does the same check. Verified against the Framework 7 javadoc. `NestedExceptionUtils.getMostSpecificCause` replaces Commons Lang's `getRootCause`.
- **Stale copies removed:** `docs/DECISIONS.md`, `docs/agent-prompts.md` and `docs/github-issues-v2.md`. `docs/` now holds only the assessment brief.
- The board export was run in Node against the board's saved choices. It reproduced the previous DECISIONS.md and CLAUDE.md byte for byte before Y1–Y4 were added, then regenerated both. The README's Assumptions section gained Y3.

### Round 9 resolution
- **All 62 decisions are answered.** The only flag left is V1-C's priority-2 time warning.
