# Issues of kgtech/nuuly-take-home (snapshot 2026-09-27, main at 317ab0c)

Every issue in the repository, read with `gh issue view <n> --json` and `gh api repos/kgtech/nuuly-take-home/issues/<n>/comments` on 2026-09-27. Bodies are verbatim, including their acceptance criteria (the `- [ ]` boxes are GitHub's; they were never ticked, and closing a PR is the record that the criteria were met). Where an issue carries `[Decision change]` comments, a "Current decision" block quotes each comment's fields (Was / Now / Why / Affects) in order, with its link; when the same decision ID appears in more than one comment, the latest comment wins. `[Triage]` chatter, `[Prompt - …]` mirrors and agent narration are left out. Issues labeled `critique` (the findings of the codebase critique, PR #20) are in the second section, "Requirements found in review".

Issues #1–#8 and #15 are the build's stories; #21 and #22 are merged critique fixes; #23 is in progress (PR #34, not merged); #24–#30 are open.

# Build stories

## #1: Project setup and Flyway schema for the ledger

- Labels: setup, persistence
- State: CLOSED (closed 2026-09-25T01:28:59Z, by PR #9)
- Created: 2026-09-24T23:12:31Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/1

### Body (verbatim)

**Labels:** `setup`, `persistence` · **Build step:** 1 · **Decisions:** D1, D2, R6, S10, D5, D9, D10, G5, G11, V1, W2

As a developer, I want a building project with the ledger schema migrated into a real Postgres, so that every later story tests against the SQL that ships.

**Scope**
- Gradle wrapper 9.1+ (pinned only in `gradle-wrapper.properties`), Kotlin DSL (`build.gradle.kts`, `settings.gradle.kts`).
- Java 25 toolchain. Spring Boot 4.1.x (built with 4.1.1), using `spring-boot-starter-webmvc` (not `-web`).
- Every library and plugin version goes in `gradle/libs.versions.toml`. Dependencies managed by the Boot BOM get no version.
- `spring-boot-starter-flyway` plus `flyway-database-postgresql`. `spring.jpa.hibernate.ddl-auto=validate`.
- Flyway `V1__inventory.sql`:
  - `sku`: `sku_id varchar(64) COLLATE "C" PRIMARY KEY`, `created_at`.
  - `inventory_ledger`: `id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY`; `sku_id varchar(64) COLLATE "C"` referencing `sku`; `quantity_delta bigint NOT NULL CHECK (quantity_delta <> 0)`; `reason text NOT NULL CHECK (reason IN ('add','purchase'))`; `created_at`.
  - `CREATE INDEX inventory_ledger_sku ON inventory_ledger (sku_id)`.
  - No idempotency table yet (story 6).
- Feature packages (`inventory/`, `idempotency/`). Classes are package-private unless another feature uses them.
- A shared Testcontainers 2.x `@ServiceConnection` Postgres test configuration (`testcontainers-postgresql`, `org.testcontainers.postgresql.PostgreSQLContainer` with an image name).

**Acceptance criteria**
- [ ] `./gradlew test` passes a context-load test against Testcontainers, with Flyway applied and Hibernate validation on.
- [ ] A ledger row with `quantity_delta = 0` fails the CHECK constraint; a row with an unknown `reason` fails too.

---
_Source: `ai/github-issues.md`, story 1._


## #2: SERIALIZABLE ledger add and purchase, with retries

- Labels: persistence
- State: CLOSED (closed 2026-09-25T03:10:52Z, by PR #10)
- Created: 2026-09-24T23:12:32Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/2

### Body (verbatim)

**Labels:** `persistence` · **Build step:** 2 · **Depends on:** #1 · **Decisions:** V1, W1, W2, X1, Y2, D3, D4, S1, S7, G2, G7, G12, V2, U1, R1, S11

As the service, I want every stock change checked and recorded in one SERIALIZABLE transaction, so that stock is never oversold, never overflows, and no add is lost, however many instances run.

**Scope**
- Writes: the `InventoryWrites` + `InventoryWritesImpl` fragment, running these statements through `JdbcClient` (no `@Modifying`). They are the canonical statements in V1's rule.
  - Add (create or add):
    ```sql
    INSERT INTO sku (sku_id) VALUES (:id) ON CONFLICT DO NOTHING;

    INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
    SELECT :id, :q, 'add'
    WHERE (SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id)
          <= 9223372036854775807 - :q
    RETURNING ((SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id) + :q)::bigint;
    ```
    No row → `Overflow`.
  - Purchase:
    ```sql
    INSERT INTO inventory_ledger (sku_id, quantity_delta, reason)
    SELECT :id, -:q, 'purchase'
    WHERE EXISTS (SELECT 1 FROM sku WHERE sku_id = :id)
      AND (SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id) >= :q
    RETURNING ((SELECT COALESCE(SUM(quantity_delta), 0) FROM inventory_ledger WHERE sku_id = :id) - :q)::bigint;
    ```
    No row → SELECT the `sku` row in the same transaction: missing → `NotFound`, otherwise `Insufficient`.
  - The RETURNING subquery doesn't see the row being inserted, so the balance is the old SUM ± `:q`.
- The ledger is append-only: never UPDATE or DELETE ledger rows, and never delete `sku` rows (G5).
- Reads: Spring Data JPA with native or projection queries. Every `SUM(quantity_delta)` is cast `::bigint`, because `SUM(bigint)` returns `numeric` (D3).
- Request quantity is `Integer` (1 to 2,147,483,647). Stored and returned quantity is `bigint` / `long`.
- The service returns `Ok` / `NotFound` / `Insufficient` / `Overflow` and never throws for a business result (R1, U1).
- Retry (W2):
  - `@EnableResilientMethods`. Retry on `PessimisticLockingFailureException` whose root SQLState is `40001` or `40P01`.
  - `@Retryable(includes = PessimisticLockingFailureException.class, predicate = SerializationFailure.class, maxRetries = 10, delay = 5, jitter = …, multiplier = 2, maxDelay = 200)`. These attribute names are verified against the Spring 7 docs.
  - Y2-A: `SerializationFailure implements MethodRetryPredicate`. Its `shouldRetry(Method, Throwable)` returns true only when `NestedExceptionUtils.getMostSpecificCause(t)` is an `SQLException` with SQLState `40001` or `40P01`. Don't add Spring Retry or Apache Commons Lang.
  - X1-B: the `@Retryable` service method runs the write through a `TransactionTemplate` set to `ISOLATION_SERIALIZABLE`, so every attempt is a new transaction. Stock-write methods have no `@Transactional`.
  - The template uses Boot's `JpaTransactionManager`; `HibernateJpaDialect` applies the isolation level to the JDBC connection (its `prepareConnection` default is `true`, so leave it on).
  - Retries exhausted → the caller gets 500 `Internal server error`, and the SKU is logged.

**Acceptance criteria (Testcontainers, S11)**
- [ ] Create path: a new SKU gets a `sku` row and its first ledger row.
- [ ] Add path: an existing SKU gets a second ledger row, and the returned balance is the sum.
- [ ] G12 guard: seed a ledger row directly so the SUM is near the maximum. Adding up to exactly 9223372036854775807 succeeds; one more returns `Overflow` and inserts no ledger row.
- [ ] Purchase returns the remaining quantity as `long`; returns `Insufficient` when stock is short and `NotFound` when the SKU is missing, inserting no ledger row in either case.
- [ ] `EXPLAIN` of the SUM query shows `inventory_ledger_sku` in use.
- [ ] A forced 40001 on the first attempt is retried in a new transaction and succeeds.
- [ ] A `PessimisticLockingFailureException` whose root SQLState is `55P03` is not retried (Y2).
- [ ] Retries running out return the 500 path and log the SKU.
- [ ] `COLLATE "C"` ordering is asserted (for example, `B` sorts before `a`).

---
_Source: `ai/github-issues.md`, story 2._


### Current decision

4 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**55P03 retry test** — [2026-09-25T03:03:00Z](https://github.com/kgtech/nuuly-take-home/issues/2#issuecomment-5825979515)

> Was: A real Postgres 55P03 reaches the service as a `PessimisticLockingFailureException` and is not retried.
> Now: `lockNotAvailableIsNotRetried` asserts root SQLState 55P03, one attempt and no rows, without requiring the PLFE type. New `StockWriteRetryFilterTest` mocks the fragment to throw `CannotAcquireLockException` (55P03 → not retried; 40001 → retried).
> Why: Without an app-provided `sql-error-codes.xml`, spring-jdbc 7 translates 55P03 to `UncategorizedSQLException`.
> Affects: `StockWriteRetryTest`, `StockWriteRetryFilterTest` (new); no production change.

**Stock-write failure logging** — [2026-09-25T03:03:01Z](https://github.com/kgtech/nuuly-take-home/issues/2#issuecomment-5825979652)

> Was: `StockWriteFailureLogger` logs every aborted stock write at ERROR with the stack trace.
> Now: ERROR with the stack trace only when retries are exhausted; a non-retried failure logs one WARN line with the SKU and root SQLState, no throwable.
> Why: The S6 catch-all handler (#3) logs the trace; logging it here too duplicates it per request.
> Affects: `StockWriteFailureLogger`, `StockWriteRetryTest.lockNotAvailableIsNotRetried`, `exhaustedRetriesPropagateAndLogSku`.

**SkuRepository base interface** — [2026-09-25T03:03:02Z](https://github.com/kgtech/nuuly-take-home/issues/2#issuecomment-5825979789)

> Was: `SkuRepository extends JpaRepository<Sku, String>, InventoryWrites`.
> Now: `SkuRepository extends Repository<Sku, String>, InventoryWrites`, declaring only `findQuantity` and `findAllQuantities`.
> Why: Removes `save`/`delete*`, so G5 and S1 are enforced by the type system.
> Affects: `SkuRepository`; later stories declare any additional reads they need.

**Index test runs the production SQL** — [2026-09-25T03:03:03Z](https://github.com/kgtech/nuuly-take-home/issues/2#issuecomment-5825979920)

> Was: `LedgerQueryPlanTest` runs EXPLAIN on hand-copied add and purchase statements.
> Now: `InventoryWritesImpl.ADD` and `PURCHASE` are package-private, and the test EXPLAINs them with `:id`/`:q` inlined, failing if any parameter remains.
> Why: An edit to the production SQL that loses `inventory_ledger_sku` would otherwise pass.
> Affects: `InventoryWritesImpl`, `LedgerQueryPlanTest.addAndPurchaseStatementsUseSkuIndex`.


## #3: The four spec operations, text/plain errors and contract tests

- Labels: api
- State: CLOSED (closed 2026-09-25T04:49:13Z, by PR #11)
- Created: 2026-09-24T23:12:33Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/3

### Body (verbatim)

**Labels:** `api` · **Build step:** 3 · **Depends on:** #2 · **Decisions:** G1, G3, G4, G5, G6, G10, G11, G13, R1, R3, R7, S2, S5, S6, S12, T3, U1, U2, U3, Y1, D6, D7

As an API client, I want the four operations in the spec with short, predictable text/plain errors, so that I can receive stock, sell it, see what's on hand, and handle errors without parsing JSON.

**Scope**
- Operations: `GET /inventory/{skuId}`, `POST /inventory/{skuId}`, `POST /inventory/{skuId}/purchase` and `GET /inventory` (every SKU with its balance, ordered by `sku_id`). Controllers are hand-written.
- Response `quantity` is a `long`.
- skuId:
  - `SkuId.isValid()` uses a precompiled `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$`. Don't put `@Pattern` or any other constraint on `@PathVariable`.
  - An invalid skuId returns 400 `Invalid request` on create, and 404 `SKU not found` on GET and purchase, without touching the database.
  - skuId is case-sensitive and compared with `equals()`.
- Check order on purchase (U3): `@Valid` body → Idempotency-Key format (story 6) → skuId pattern (404) → service. A bad body on a missing SKU returns 400 (G4-C).
- Outcome mapping (through the error helper):
  - `Ok` → 200.
  - `NotFound` → 404 `SKU not found`.
  - `Insufficient` → 400 `Insufficient inventory`.
  - `Overflow` → 400 `Invalid request`.
  - Retries exhausted → 500 `Internal server error`.
- Errors:
  - One `@RestControllerAdvice`. Every error response goes through one helper that calls `.contentType(MediaType.TEXT_PLAIN)`. ProblemDetail stays off.
  - Bodies are exactly one of `SKU not found`, `Insufficient inventory`, `Invalid request`, `Internal server error`. Any other status uses `HttpStatus.getReasonPhrase()`. Bodies never include stock counts.
  - On both POSTs, every client request error is 400 `Invalid request`: malformed JSON, a missing body, a wrong or missing Content-Type (instead of 415).
  - Accept (U2): a filter sets `Accept: application/json` on `GET /inventory/**`; on POST, `HttpMediaTypeNotAcceptableException` → 400 `Invalid request`.
  - Y1-A: both POST mappings declare `consumes` and `produces = MediaType.APPLICATION_JSON_VALUE`, so an unacceptable Accept fails at handler lookup, before the controller or the ledger write runs.
  - Outside the spec's operations, Spring's 404 and 405 are kept with reason-phrase bodies. 405 keeps its `Allow` header.
  - A `@Hidden @ExceptionHandler(Exception.class)` catch-all logs at ERROR and returns 500 `Internal server error`. An `ErrorResponse` keeps its status.
  - `/actuator/**` and the springdoc paths keep their library behaviour (S6).
- Jackson strict parsing (G13): `spring.jackson.mapper.allow-coercion-of-scalars=false`, `spring.jackson.deserialization.accept-float-as-int=false`, a null `quantity` rejected, unknown properties ignored, `Integer` in the DTO.
- A parameterized MockMvc contract test with one row per response in the original spec, asserting status, Content-Type and the exact body (S12).

**Acceptance criteria**
- [ ] The contract table covers GET 200/404, POST 200/400, purchase 200/400 (insufficient)/400 (invalid)/404, and list 200 (including the empty array).
- [ ] Each error status is tested with `Accept: application/json` and asserts `Content-Type: text/plain` and the exact body.
- [ ] `{"quantity":"10"}`, `{"quantity":10.5}`, `{"quantity":null}`, `{}`, a missing body and `Content-Type: text/xml` each return 400 on both POSTs. `{"quantity":5,"extra":1}` is accepted.
- [ ] GET with `Accept: application/xml` returns 200 JSON. POST with `Accept: application/xml` returns 400 `Invalid request`, and no ledger row is written (Y1).
- [ ] `DELETE /inventory/x` returns 405 `Method Not Allowed` with an `Allow` header. `/nope` returns 404 `Not Found`.
- [ ] A SKU sold down to 0 returns quantity 0 and still appears in the list. `ABC` and `abc` are separate SKUs.
- [ ] A balance above 2,147,483,647 (built from two adds) is returned correctly.
- [ ] A forced RuntimeException returns 500 `Internal server error` as text/plain.

---
_Source: `ai/github-issues.md`, story 3._


## #4: Concurrency tests

- Labels: testing
- State: CLOSED (closed 2026-09-25T05:38:53Z, by PR #12)
- Created: 2026-09-24T23:12:34Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/4

### Body (verbatim)

**Labels:** `testing` · **Build step:** 4 (spec complete) · **Depends on:** #3 · **Decisions:** D9, S11, W2, G7

As a reviewer, I want proof that concurrent requests never oversell or lose an add, so that I can trust the SERIALIZABLE design without a CHECK constraint behind it.

**Scope**
- Tests run through HTTP against Testcontainers Postgres. They are not `@Transactional` and clean the tables in `@BeforeEach`.
- At most 8 threads per SKU (W2-A), started together with a latch.

**Acceptance criteria**
- [ ] Concurrent purchase: N ≤ 8 threads buy 1 against stock M < N. Exactly M get 200, the rest get 400 `Insufficient inventory`, and the final quantity is 0 (the SUM never goes negative).
- [ ] Concurrent add: N ≤ 8 threads add 1 to a new SKU. All return 200 and the final quantity is N.
- [ ] Neither test sees a 500 (retries don't run out at N ≤ 8).
- [ ] 🏁 Every spec operation is built and tested.

---
_Source: `ai/github-issues.md`, story 4._


## #5: Docker Compose, health checks and a clean-clone run

- Labels: setup
- State: CLOSED (closed 2026-09-25T07:03:50Z, by PR #13)
- Created: 2026-09-24T23:12:35Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/5

### Body (verbatim)

**Labels:** `setup` · **Build step:** 5 (submittable) · **Depends on:** #4 · **Decisions:** D8, S4, D10, S6

As a reviewer, I want to start the app and Postgres with one command, so that I can try the API with minimal setup.

**Scope**
- `compose.yaml` defines only Postgres, with a `pg_isready` healthcheck.
- `compose.override.yaml` adds the app: `build: .`, port 8080, `depends_on: postgres` with `condition: service_healthy`.
- A `Dockerfile` for the app.
- `spring-boot-docker-compose` as `developmentOnly`, so `./gradlew bootRun` starts only Postgres.
- Don't set `COMPOSE_FILE` or `COMPOSE_PROFILES` in `.env`.
- Actuator exposes `/actuator/health` with liveness and readiness.

**Acceptance criteria**
- [ ] `docker compose up --build` starts Postgres and the app, and `GET /actuator/health` returns `UP`.
- [ ] `./gradlew bootRun` starts Postgres through Boot's Compose support and the app on port 8080.
- [ ] A fresh clone plus the README's commands works.
- [ ] 🏁 The repo is submittable at this point.

---
_Source: `ai/github-issues.md`, story 5._


### Current decision

1 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**S10: container image tags may repeat catalog versions** — [2026-09-25T06:07:14Z](https://github.com/kgtech/nuuly-take-home/issues/5#issuecomment-5827695684)

> Was: Library and plugin versions are written only in gradle/libs.versions.toml; the Gradle version only in gradle-wrapper.properties.
> Now: Same, with one exception: compose.yaml and the Dockerfile repeat the catalog's postgres and java versions in their image tags (postgres:18, eclipse-temurin:25), and ComposeFilesTest checks the compose postgres tag against the catalog.
> Why: bootRun and docker compose read compose.yaml directly, so image tags cannot be sourced from the version catalog.
> Affects: ai/decision-board.html, CLAUDE.md, DECISIONS.md (regenerated), compose.yaml, Dockerfile, ComposeFilesTest


## #6: Optional Idempotency-Key on both POSTs

- Labels: persistence, api
- State: CLOSED (closed 2026-09-25T16:24:37Z, by PR #14)
- Created: 2026-09-24T23:12:36Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/6

### Body (verbatim)

**Labels:** `api`, `persistence` · **Build step:** 6 · **Depends on:** #5 · **Decisions:** G8, G14, R1, R2, R9, S3, S8, T1, U1, U3, W1, W2, X1, Y1, Y3, Y4, S11

As an API client, I want to retry a POST safely, so that a network retry doesn't double-count stock.

**Scope**
- Flyway `V2__idempotency.sql`: `idempotency_key uuid PRIMARY KEY`, plus operation, sku_id, request_hash, status, content_type (Y4), body and created_at.
- `request_hash` is SHA-256 of `operation + "\n" + skuId + "\n" + quantity`, computed from the parsed, validated request. Never hash the raw body (Y3).
- The header is optional. Without it, behaviour is exactly as in the original spec.
- A present but empty or non-UUID header → 400 `Invalid request` before any database work (U3 order). Not stored.
- The claim runs inside the same TransactionTemplate callback as the ledger insert (story 2, X1-B):
  - Claim with `INSERT … ON CONFLICT DO NOTHING RETURNING`.
  - No row → SELECT the stored response: a different operation, skuId or request hash → 400; a key older than 24h → 400 (never reused, never purged); otherwise replay the stored status, Content-Type and body unchanged (Y4).
  - A concurrent claim of the same key may raise 40001 instead (unverified). The W2 retry then runs a new transaction, finds the stored row and replays it.
- Store 200, 404 `SKU not found`, 400 `Insufficient inventory` and the overflow 400 `Invalid request`. Validation 400s are not stored.
- Add a `@Parameter` for the header to the springdoc annotations.

**Acceptance criteria (Testcontainers)**
- [ ] The first request claims the key. A replay with the same body returns an identical status and body, and exactly one ledger row exists.
- [ ] Reusing the key with a different body, on a different SKU, or on the other POST returns 400.
- [ ] A key whose row is older than 24h returns 400.
- [ ] Two concurrent requests with the same fresh key produce one ledger row, and both get the same response.
- [ ] A replayed 404, `Insufficient inventory` and overflow 400 are returned as stored.
- [ ] An empty key and a non-UUID key return 400 and leave no idempotency row.
- [ ] The same key and quantity with different whitespace or an extra unknown field replays; a different quantity returns 400 (Y3).
- [ ] A replayed 200 has Content-Type `application/json`; a replayed 404 or 400 has `text/plain` (Y4).
- [ ] A keyed POST with `Accept: application/xml` returns 400 and writes no idempotency row (Y1).

---
_Source: `ai/github-issues.md`, story 6._


### Current decision

5 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**Y4: the idempotency response columns are set in the claim's transaction instead of being NOT NULL at insert** — [2026-09-25T07:41:58Z](https://github.com/kgtech/nuuly-take-home/issues/6#issuecomment-5828782799)

> Was: The idempotency table stores content_type text NOT NULL with status and body.
> Now: status, content_type and body are nullable at the R2 claim and set by an UPDATE in the same transaction; a CHECK allows them only all NULL or all set, so no committed row is incomplete.
> Why: R2 claims the key with INSERT … ON CONFLICT before the response exists, so NOT NULL columns cannot be filled at claim time.
> Affects: V2__idempotency.sql, IdempotencyStore (claim/complete SQL), IdempotencySchemaTest, ai/decision-board.html, DECISIONS.md, CLAUDE.md (Y4 rule)

**IdempotencyStore stereotype** — [2026-09-25T08:15:03Z](https://github.com/kgtech/nuuly-take-home/issues/6#issuecomment-5829187126)

> Was: IdempotencyStore is a public @Repository (JdbcClient).
> Now: IdempotencyStore is a public @Component (JdbcClient).
> Why: @Repository exception translation rewraps the documented IllegalStateException as InvalidDataAccessApiUsageException; JdbcClient still translates SQL errors, so 40001 retries are unchanged.
> Affects: IdempotencyStore.java, IdempotencyStoreTest (unchanged, now passes)

**Z1: Idempotency-Key handling, skuId checks and the idempotency transaction move to a service-layer @Idempotent interceptor** — [2026-09-25T14:29:23Z](https://github.com/kgtech/nuuly-take-home/issues/6#issuecomment-5834090614)

> Was: The controller validated the Idempotency-Key and skuId and called keyed service overloads that ran IdempotencyStore.execute inside the service's SERIALIZABLE TransactionTemplate (REQUIRES_NEW, issue #2).
> Now: The controller passes the raw skuId and header to the service. @Idempotent on the service's stock-write methods is applied by a spring-aop Advisor (no new dependency). Inside @Retryable ([Retry, Idempotency, Tx]) it checks the key and skuId, opens a SERIALIZABLE REQUIRES_NEW transaction, then claims, writes and stores. The service's TransactionTemplate is REQUIRED and fails fast inside a non-SERIALIZABLE transaction. Rejections are WriteResult outcome values. HTTP behaviour and replay bytes are unchanged. IdempotencyStore becomes a package-private @Component.
> Why: PR #14 owner review. Idempotency is cross-cutting (Idempotent Receiver), and the service layer, not the controller, converts transport values.
> Affects: InventoryController, InventoryService, SkuId, OutcomeResponses, idempotency/ (new Idempotent, IdempotentResults, IdempotencyInterceptor, IdempotencyConfiguration), rules X1, S2, G11, U3, S3, S1, D3, R1 (board card Z1), controller/service/WebMvc tests

**Z1 rule wording and IdempotentResults.toStored signature** — [2026-09-25T14:39:10Z](https://github.com/kgtech/nuuly-take-home/issues/6#issuecomment-5834233895)

> Was: "InventoryController and InventoryService never reference idempotency types"; IdempotentResults.toStored(R result).
> Now: They reference no idempotency types other than the @Idempotent annotation and Operation (the aspect's declarative contract); IdempotentResults.toStored(String skuId, R result).
> Why: The approved design annotates the service with @Idempotent(Operation); the stored 200 body needs the skuId, which StockOutcome.Ok does not carry.
> Affects: ai/decision-board.html, CLAUDE.md (Z1 rule 0), IdempotentResults, IdempotencyInterceptor, OutcomeResponses, OutcomeResponsesTest, IdempotencyInterceptorTest

**Z2: inventory splits into domain and web packages; header names come from HttpConstants** — [2026-09-25T15:09:15Z](https://github.com/kgtech/nuuly-take-home/issues/6#issuecomment-5834694404)

> Was: D10: one flat inventory package; classes package-private unless another feature uses them. The controller declared "Idempotency-Key" itself and used qualified nested type names.
> Now: com.kgtech.inventoryapi.inventory (domain) and com.kgtech.inventoryapi.inventory.web (controller, advice, filter, TextErrors, InventoryQuantity, OutcomeResponses). Domain contract types consumed by web are public; persistence internals stay package-private; the domain imports no Spring MVC/HTTP types. Header names come from com.kgtech.inventoryapi.web.HttpConstants or Spring constants; controllers use static imports and imported nested types.
> Why: PR #14 owner review: no hardcoded strings, no business types in the web layer, lean imports.
> Affects: package layout of inventory/, new web/HttpConstants, visibility of domain contract types, D10 rule (board card Z2), tests under inventory/ (package moves)


## #7: Opt-in keyset paging for GET /inventory

- Labels: api
- State: CLOSED (closed 2026-09-26T01:26:38Z, by PR #18)
- Created: 2026-09-24T23:12:37Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/7

### Body (verbatim)

**Labels:** `api` · **Build step:** 7 · **Depends on:** #5 · **Decisions:** G9, R4, R8, S11

As an API client with a large catalogue, I want to page through inventory, so that I don't have to download every SKU at once.

**Scope**
- Optional `limit` (1–250) and `after` query parameters. Without them, every SKU is returned as a bare array (unchanged).
- Page over `sku` with `WHERE sku_id > :after ORDER BY sku_id LIMIT :limit + 1`, then sum each page's ledger rows (`::bigint`). If the extra row exists, add `Link: <…?limit=N&after=LAST>; rel="next"` with `after` URL-encoded.
- Never 400: a non-positive or non-numeric `limit` is ignored; above 250 is treated as 250; `after` is a plain string, never validated, and `after` alone returns every SKU after it.

**Acceptance criteria (Testcontainers)**
- [ ] Following the Link headers walks every SKU exactly once, and the last page has no Link header.
- [ ] Each page's quantities match the ledger SUM for those SKUs.
- [ ] `limit=0`, `limit=-1`, `limit=abc` and `limit=9999` all return 200.
- [ ] `after` alone returns every SKU after it.

---
_Source: `ai/github-issues.md`, story 7._


### Current decision

2 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**Z2 domain package list gains InventoryPage** — [2026-09-25T20:10:25Z](https://github.com/kgtech/nuuly-take-home/issues/7#issuecomment-5838864284)

> Was: Z2 lists the domain package classes (… SkuId, InventoryItem, SerializationFailure, StockWriteFailureLogger); no paging type.
> Now: InventoryPage (public record with nested Next) is added to the domain package list; lenient limit parsing stays a private helper in InventoryService (no new class).
> Why: Paging returns items plus an optional next cursor to the web layer, and Z2 requires business types to live in the domain package.
> Affects: ai/decision-board.html, CLAUDE.md (regenerated), InventoryService, InventoryController

**New decision Z3: GET /inventory answers 400 "Invalid request" for a query string it can't read unambiguously** — [2026-09-25T20:49:11Z](https://github.com/kgtech/nuuly-take-home/issues/7#issuecomment-5839395641)

> Was: R4 — GET /inventory never returns 400; an undecodable query string (e.g. after=%zz) surfaced as 500 via the catch-all; a repeated after was joined by Spring ("A,B") and used as the cursor.
> Now: InventoryErrorAdvice maps Tomcat's InvalidParameterException (malformed percent-escape or invalid UTF-8) to 400 "Invalid request" text/plain; a repeated after returns the same 400 (a repeated limit is still ignored). GET /inventory's OpenAPI documents the 400, and the after parameter says it must not be repeated. R4, G10 and S12 are refined accordingly.
> Why: PR #18 review R1-1/R1-2: a client error must not answer 500, and the list is ordered by the unique sku_id, so a second cursor value has no meaning.
> Affects: InventoryErrorAdvice, InventoryController (after binding, springdoc), README assumptions, ApiDocsTest, new raw-HTTP integration test, CLAUDE.md/DECISIONS.md/ai/decision-board.html


## #8: OpenAPI export, final README and agent-prompts.md

- Labels: api, docs
- State: CLOSED (closed 2026-09-26T03:43:38Z, by PR #19)
- Created: 2026-09-24T23:12:38Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/8

### Body (verbatim)

**Labels:** `docs`, `api` · **Build step:** 8, finished last · **Decisions:** D7, S12, D0, S9, S10, T6, R2, T1, D4, V1

As a Nuuly reviewer, I want generated API docs, clear run instructions and a record of how AI was used, so that I can check the build against the contract and see how AI was directed.

**Scope**
- springdoc-openapi 3.1.x serves `/v3/api-docs` and Swagger UI. Set `springdoc.override-with-generic-response=false`.
- Each controller method declares `@ApiResponse` for exactly the spec's codes; error responses use `mediaType = "text/plain"`. Response `quantity` is `int64`; request `quantity` is `int32` with minimum 1.
- A test writes `/v3/api-docs` to `openapi.yaml`, asserts that every error response is text/plain, and the file is committed.
- README:
  - Check the build and run commands against the real build and remove the "Status" note.
  - Add prerequisites and curl examples for each operation.
  - Future improvements: Redis in front of the idempotency table (R2, T1), timed reservations (D4), periodic balance snapshots for the ledger (V1).
  - "Designed, not built", filled in at the hour-20 stop with decision IDs.
- The layout stays as D0: `CLAUDE.md`, `DECISIONS.md` and `agent-prompts.md` at the root; the board, review, sources and these issues in `ai/`.
- `DECISIONS.md` changes only by regenerating it from the board (S9).
- `agent-prompts.md` gets an entry for every AI session: prompt, output summary, accepted, rejected, response.

**Open verifications**
- Whether springdoc emits `*/*` for responses with no annotation. The generated-file assertion catches it either way.

**Acceptance criteria**
- [ ] `openapi.yaml` is regenerated by the test and committed.
- [ ] A fresh clone plus `docker compose up --build` works by following the README alone.
- [ ] `DECISIONS.md` matches the board's export.
- [ ] `agent-prompts.md` has an entry for every AI session, including the planning sessions.

---
_Source: `ai/github-issues.md`, story 8._


### Current decision

2 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**OpenAPI operations are tagged "inventory"** — [2026-09-26T02:46:28Z](https://github.com/kgtech/nuuly-take-home/issues/8#issuecomment-5842498892)

> Was: OQ1-B: operationIds, summaries and info aligned with the spec; springdoc's generated tag "inventory-controller" left on every operation
> Now: class-level @Tag(name = "inventory") on InventoryController; openapi.yaml regenerated
> Why: the generated tag exposes a Java class name and is not in the original spec (PR #19 review R1-4)
> Affects: InventoryController, openapi.yaml, ApiDocsTest (new operationsTaggedInventory)

**The committed openapi.yaml declares the server as http://localhost:8080** — [2026-09-26T03:25:43Z](https://github.com/kgtech/nuuly-take-home/issues/8#issuecomment-5842756816)

> Was: the export kept the MockMvc default server http://localhost, and the README noted it as a placeholder (PR #19 review R1-2)
> Now: ApiDocsTest exports /v3/api-docs.yaml from a request on port 8080, so the committed file says http://localhost:8080; the live /v3/api-docs still reports the requested host and port; the README placeholder note is removed
> Why: the app runs on 8080, and the committed documentation should reflect the correct port
> Affects: ApiDocsTest, openapi.yaml, README.md


## #15: Fix PR #14 round-3 review findings (StoredResponse rendering, rejection logging, header literals)

- Labels: (none)
- State: CLOSED (closed 2026-09-25T17:31:16Z, by PR #16)
- Created: 2026-09-25T17:06:15Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/15

### Body (verbatim)

Follow-up to #6 / PR #14. The round-3 review of the Z1/Z2 redesign (at 58bb1a7) found three issues that were not posted before the PR merged.

**Decisions:** Z1, Z2, R1, Y4, S5, W2

**Scope**
1. **R3-1 (MINOR): the domain result type carries an HTTP response.** `WriteResult.Stored` (domain) wraps `idempotency.StoredResponse`, whose `toResponseEntity()` returns a Spring `ResponseEntity`. The web controller calls `stored.response().toResponseEntity()`, which uses an idempotency type other than `@Idempotent`/`Operation` (Z1).
   - Make `StoredResponse` a plain record (status, contentType, body) with no Spring HTTP imports.
   - Move the `ResponseEntity` rendering into `inventory.web`.
   - Replay bytes, status and Content-Type stay unchanged (Y4).
2. **R3-2 (MINOR): nothing tests that rejections fire no retry event.** Z1 says rejections are outcome values, so a 400/404 fires no `MethodRetryEvent`. No test checks this. Add tests showing that these produce no `MethodRetryEvent` and no `StockWriteFailureLogger` WARN line:
   - a malformed Idempotency-Key
   - a keyed malformed skuId (create and purchase)
   - a reused key with a different request
   - an unkeyed malformed skuId
3. **R3-3 (NIT): header-name literals remain in tests.**
   - Replace `"Accept"`, `"Allow"` and `"Content-Type"` string literals in tests with `HttpHeaders` constants: `InventoryRequestValidationTest`, `JsonAcceptForGetFilterTest`, `InventoryErrorAdviceTest`, `InventoryHttpConcurrencyTest`, and any others found.
   - Extend `PackageBoundaryTest` so that no class in the `idempotency` or domain `inventory` packages imports `org.springframework.http.*`.

**Acceptance criteria**
- [ ] `StoredResponse` and every class in the domain `inventory` package import no `org.springframework.http.*` or `org.springframework.web.*` types, and `PackageBoundaryTest` enforces it.
- [ ] The web layer renders stored responses; a replayed 200, 404 and 400 have the same status, Content-Type and body as before (existing Y4 tests stay green unchanged).
- [ ] Tests prove that none of the four rejection cases above publishes a `MethodRetryEvent` or logs a WARN from `StockWriteFailureLogger`.
- [ ] No `"Accept"`, `"Allow"`, `"Content-Type"` or `"Idempotency-Key"` string literals are used as header names in `src/test`.
- [ ] `./gradlew clean build --warning-mode=fail` is green.


# Requirements found in review

Issues labeled `critique`, created 2026-09-26 from the codebase critique of main at 211bd95 (PR #20, review only). Each carries the critique's finding IDs (C-nn). #21 and #22 are merged into main; #23 has an open PR (#34); #24–#30 have not started. Their acceptance criteria are part of the scope.

## #21: C-01, C-07, C-06: Keep error responses text/plain outside Spring MVC and scope the advice (S6)

- Labels: critique
- State: CLOSED (closed 2026-09-26T18:22:10Z, by PR #31)
- Created: 2026-09-26T15:10:57Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/21

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-01 (MAJOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#pullrequestreview-5324666352
- Location: missing
- Finding: Requests Tomcat rejects before Spring runs (encoded slash, malformed or invalid-UTF-8 escape, NUL in the path, oversized request line or header, missing Host) get Tomcat's ErrorReportValve HTML page. No Tomcat or valve customization exists; InventoryErrorAdvice.java:35-40 covers only query decoding (Z3).
- Failure scenario: `GET /inventory/A%2FB` (a client encoding SKU `A/B`) returns 400 text/html. G11 requires 404 text/plain "SKU not found"; the spec lists only 200/404 for this operation.
- Evidence: `curl -sS -i --path-as-is localhost:8080/inventory/a%2Fb` → `HTTP/1.1 400`, `Content-Type: text/html;charset=utf-8`, `<!doctype html>…<title>HTTP Status 400 – Bad Request</title>`. Same for `POST /inventory/a%2Fb/purchase`, `/inventory/a%zzb`, `/inventory/a%C3%28`. No test covers it (MockMvc bypasses Tomcat); ai/research-sources.md:62 marks the %2F behaviour unverified.
- Related: #3, #7; G6, G10, G11, S5, Z3

### C-07 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#pullrequestreview-5324666352
- Location: missing
- Finding: TRACE is rejected by Tomcat (`allowTrace=false`) via `sendError(405)`, and Boot's BasicErrorController renders a JSON body, bypassing the advice. Its `Allow` header lists methods the API doesn't support.
- Failure scenario: `TRACE /inventory/ABC-1` returns 405 application/json with `Allow: HEAD, DELETE, POST, GET, OPTIONS, PUT`; PUT and DELETE then return 405.
- Evidence: `curl -i -X TRACE localhost:8080/inventory/ABC-1` → `405`, `Content-Type: application/json`, `{"timestamp":…,"status":405,"error":"Method Not Allowed","path":"/inventory/ABC-1"}`. PUT gives 405 text/plain with `Allow: GET, POST`. No test sends TRACE.
- Related: #3; G10, R3, T3

### C-06 (decision challenge, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250818
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/web/InventoryErrorAdvice.java:22`
- Finding: The `@RestControllerAdvice` is unscoped, so its handlers rewrite errors on /actuator/** and springdoc paths, which S6/G10 say keep library behaviour. Scoping it to the controller would lose the text/plain 404/405 for unknown /inventory/** paths, so S6 needs a ruling.
- Failure scenario: Monitoring and tooling receive inventory-contract text/plain bodies, e.g. "Invalid request" from the health endpoint.
- Evidence: `/actuator/health` with `Accept: application/xml` → 406 text/plain "Not Acceptable"; `/actuator/nope` → 404 text/plain "Not Found"; `/actuator/health?x=%zz` → 400 text/plain "Invalid request"; `/v3/api-docs` with `Accept: application/xml` → 406 text/plain. `ActuatorHealthTest#onlyHealthIsExposed` asserts status only.
- Related: PR #18; S6, G10, Z3

## Acceptance criteria

- [ ] Requests Tomcat rejects before Spring (encoded slash, malformed or invalid-UTF-8 escape, NUL, oversized request line or header, missing Host) return text/plain with no HTML; under /inventory/**, `GET /inventory/A%2FB` and `POST /inventory/A%2FB/purchase` return 404 "SKU not found" (G11), `POST /inventory/A%2FB` returns 400 "Invalid request", and other rejections return 400 "Invalid request".
- [ ] `TRACE` on /inventory/** returns 405 text/plain "Method Not Allowed" with the same `Allow` header as other unsupported methods (`GET, POST` or `POST`).
- [ ] S6 is refined on the board (decision change, regenerated DECISIONS.md/CLAUDE.md): /actuator/** and springdoc paths keep library behaviour; /inventory/** and unknown paths keep the text/plain contract. `/actuator/health` with `Accept: application/xml`, `/actuator/nope`, `/actuator/health?x=%zz` and `/v3/api-docs` with `Accept: application/xml` no longer return inventory text/plain bodies.
- [ ] Tests through a real server (RANDOM_PORT, raw HTTP where needed) fail at baseline and pass after the fix for each case above; MockMvc-only tests do not count for the Tomcat cases.
- [ ] No existing /inventory/** status, Content-Type or body changes.


### Current decision

2 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**New decision C1; S6, G10, R3 and S5 refined** — [2026-09-26T15:35:18Z](https://github.com/kgtech/nuuly-take-home/issues/21#issuecomment-5847522144)

> Was: S6 "G10 covers /inventory/** only. /actuator/** and springdoc paths keep library behaviour." (the advice was unscoped in practice); G10 allowed only the spec's codes on its operations; R3 limited 400 to the two POSTs; S5 named only the advice and controller as error builders. Requests Tomcat rejects before routing got its HTML page; TRACE got Spring Boot's JSON.
> Now: C1: Tomcat's host ErrorReportValve is replaced by a text/plain TextErrorReportValve (400 → "Invalid request", other statuses → reason phrase); the connector passes %2F through so /inventory/A%2FB reaches SkuId.isValid (GET/purchase 404, create 400); TRACE is routed through Spring MVC like PUT (405 text/plain with Spring's Allow, or 404); InventoryErrorAdvice rethrows on /actuator/** and springdoc paths so they keep Spring Boot's own responses. G10 and R3 allow 400 "Invalid request" for a request Tomcat rejects before routing on any operation; S5 lets the valve take its body from TextErrors.textFor.
> Why: Critique findings C-01, C-06, C-07 (PR #20): container-level rejections broke the text/plain contract and G11's 404, and the unscoped advice rewrote library-path errors.
> Affects: InventoryErrorAdvice, new ServletContainerConfiguration and TextErrorReportValve (inventory.web), new real-server tests, README Assumptions, ai/research-sources.md, ai/decision-board.html, DECISIONS.md, CLAUDE.md (commit 4bceec1).

**Undecodable query string: 400 text/plain and one WARN line on every path (C1 amended, Z3 refined)** — [2026-09-26T17:35:54Z](https://github.com/kgtech/nuuly-take-home/issues/21#issuecomment-5848372513)

> Was: On /actuator/** and the springdoc paths, InventoryErrorAdvice rethrew InvalidParameterException, so Spring Boot answered 400 application/json and Tomcat logged an ERROR with a stack trace; elsewhere the advice returned 400 "Invalid request" and logged at DEBUG.
> Now: A query string Tomcat can't decode is answered by InventoryErrorAdvice on every path, library paths included: 400 text/plain "Invalid request" and one WARN log line with the method and path, no stack trace. It is never rethrown. Other library-path errors still keep Spring Boot's behaviour.
> Why: PR #31 round-1 finding R1-2; the error must be visible in the application logs as a warning, with the text/plain 400 response.
> Affects: InventoryErrorAdvice.undecodableQuery; LibraryPathErrorsIntegrationTest (actuator undecodable query), log assertions; README Assumptions; ai/decision-board.html, DECISIONS.md, CLAUDE.md (commit 3a9a5dc).


## #22: C-02, C-08, C-09: Default page limit on GET /inventory (G9), Link encoding, and OpenAPI constraints

- Labels: critique
- State: CLOSED (closed 2026-09-27T17:10:06Z, by PR #33)
- Created: 2026-09-26T15:10:58Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/22

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-02 (decision challenge, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250800
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/InventoryService.java:88`-92
- Finding: Without `limit` (and with `after` alone), `list()` materializes every SKU; `findAllQuantities` aggregates the whole ledger. No auth (G10), no SKU deletion (G5), no server-side cap.
- Failure scenario: An unauthenticated client creates ~1M SKUs; concurrent default `GET /inventory` calls exhaust the heap and return 500.
- Evidence: Compose stack, 1,000,000 SKUs seeded by SQL, 2 GB heap: one `GET /inventory` → 200, 35 MB, 4.3 s; 8 concurrent → all 8 returned 500, 16 `java.lang.OutOfMemoryError: Java heap space` in the app log. SkuRepository.java:21-25; `InventoryServiceReadTest#listWithAfterOnlyIsUnbounded` pins the after-only case.
- Related: #7, PR #18; G9, R4, G5, G10

### C-08 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250820
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/web/InventoryController.java:138`-146
- Finding: `fromCurrentRequestUri()` starts from the raw, still-encoded URI and `.encode()` encodes it again, so `%` in the path becomes `%25` in the next-page Link.
- Failure scenario: `GET /%69nventory?limit=1` returns `Link: <http://localhost:8080/%2569nventory?limit=1&after=ABC-1>; rel="next"`; following it returns 404.
- Evidence: `curl -i --path-as-is 'localhost:8080/%69nventory?limit=1'` → Link above; `curl -i --path-as-is 'localhost:8080/%2569nventory?limit=1&after=ABC-1'` → 404 text/plain "Not Found". `InventoryListPagingTest` covers encoding of `after` only.
- Related: #7; G9

### C-09 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250822
- Location: `openapi.yaml:57`-62
- Finding: The skuId path parameters are a bare `type: string` (also lines 82-86, 122-126): no G11 pattern or maxLength. `InventoryItem.quantity` (166-173) drops the original spec's `minimum: 0`. `@Parameter(schema = …)` documents the pattern without violating S2's no-`@Pattern` rule.
- Failure scenario: A client generated from openapi.yaml accepts a 65-character or space-containing SKU and receives an undocumented 400 on create.
- Evidence: `POST /inventory/<65×A>` → 400 "Invalid request"; `POST /inventory/%E2%82%AC` → 400. ApiDocsTest has no assertion on the skuId schema.
- Related: #8; D7, S12, G11, R7

## Acceptance criteria

- [ ] G9 (and R4 where affected) are changed on the board: when `limit` is absent, GET /inventory returns at most 250 rows (the maximum) and a `Link: rel="next"` header when more exist. `after` alone is also capped. Decision change posted on this issue; DECISIONS.md/CLAUDE.md regenerated.
- [ ] A test seeding more than 250 SKUs fails at baseline and passes after: unpaged GET returns 250 items and a Link; following Links visits every SKU once.
- [ ] The next-page Link is built from the decoded routed path, so `GET /%69nventory?limit=1` yields a Link to `/inventory?...` that returns 200; a test fails at baseline and passes after.
- [ ] openapi.yaml documents the skuId pattern `^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$` (and maxLength 64) on all three path parameters via `@Parameter(schema = ...)` (no `@Pattern`, per S2), `minimum: 0` on `InventoryItem.quantity`, and the default limit. ApiDocsTest asserts each; openapi.yaml regenerated and committed.
- [ ] README (Try it, Assumptions) and the openapi descriptions describe the new default.


### Current decision

3 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**New decision C2: default page of 250 on GET /inventory; G9, R4, R8 and S11 refined** — [2026-09-26T20:13:54Z](https://github.com/kgtech/nuuly-take-home/issues/22#issuecomment-5849515125)

> Was: G9 "Without them return every row"; R4 "after alone returns every row after it"; GET /inventory without limit (or with after alone) returned every SKU and aggregated the whole ledger.
> Now: GET /inventory always runs the keyset query with a page size: limit when R4 accepts it, otherwise 250 (R8's maximum), with a Link carrying limit and after when more rows exist; after alone is capped the same way. The next-page Link is built from the request's scheme, host, port and context path plus the routed path /inventory. The OpenAPI documents the skuId pattern, minLength 1 and maxLength 64 via @Parameter(schema), minimum 0 on InventoryItem.quantity, and the default page.
> Why: Critique finding C-02 (PR #20): with 1,000,000 SKUs, 8 concurrent unpaged GET /inventory calls returned 500 with OutOfMemoryError. C-08 and C-09 are fixed under the same card.
> Affects: InventoryService.list, SkuRepository (findAllQuantities removed), InventoryController (nextLink, springdoc annotations), SkuId, InventoryItem, openapi.yaml, README (paging, API docs, Assumptions), paging tests; ai/decision-board.html, DECISIONS.md, CLAUDE.md (commit 8c93f11).

**Web-layer API strings live in InventoryApi (C2 amended, Z2 refined)** — [2026-09-27T07:00:01Z](https://github.com/kgtech/nuuly-take-home/issues/22#issuecomment-5853601017)

> Was: The base path /inventory appeared in InventoryController (package-private BASE_PATH with a static self-import) and JsonAcceptForGetFilter; query parameter names and OpenAPI description strings were private controller constants.
> Now: inventory.web.InventoryApi holds the base path, the query parameter names and the OpenAPI description texts; InventoryController, JsonAcceptForGetFilter and the Link builder static-import them, so @RequestMapping(BASE_PATH) needs no literal and no qualified name. Domain constants stay beside their logic (SQL in repositories, error texts in TextErrors, the skuId pattern in SkuId, page sizes in InventoryService).
> Why: Owner decision on PR #33 round 1: constants must be in one place for readability, otherwise string literals.
> Affects: new InventoryApi.java; InventoryController; JsonAcceptForGetFilter; ai/decision-board.html, DECISIONS.md, CLAUDE.md (commit 4a8caea).

**InventoryApi holds the API paths, including the item and purchase templates (C2 wording)** — [2026-09-27T15:33:19Z](https://github.com/kgtech/nuuly-take-home/issues/22#issuecomment-5857264952)

> Was: C2 said InventoryApi holds "the base path /inventory"; Z2 (as refined) said "API paths"; `/{skuId}` and `/{skuId}/purchase` stayed as literals in InventoryController.
> Now: InventoryApi holds the API paths (the base path /inventory and the item and purchase path templates), the query parameter names and the OpenAPI texts; mapping annotations static-import them.
> Why: PR #33 round-2 finding R2-3; owner chose to move the templates, consistent with keeping constants together.
> Affects: InventoryApi, InventoryController; ai/decision-board.html, CLAUDE.md (commit 6b1e087).


## #23: C-04, C-05, C-34, C-13: skuId path handling, invalid-ID GET without the database, Accept q=0, and filter regression tests

- Labels: critique
- State: OPEN (in progress: PR #34, not merged; not part of main at 317ab0c)
- Created: 2026-09-26T15:10:59Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/23

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-04 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250805
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/web/InventoryController.java:75`-81
- Finding: A raw `;` in the path segment is treated as a matrix parameter and stripped before `@PathVariable` binding, so the G11 pattern check sees a different, valid SKU. Downgraded from MAJOR: the encoded form `%3B` is rejected correctly.
- Failure scenario: `POST /inventory/ABC-1;lot=7 {"quantity":5}` should be 400 "Invalid request"; it adds 5 to ABC-1. `POST /inventory/ABC-1;x/purchase` decrements ABC-1. A keyed request stores `sku_id = ABC-1`.
- Evidence: `curl -X POST --path-as-is 'localhost:8080/inventory/ABC-1;lot=7' -d '{"quantity":5}'` → `200 {"skuId":"ABC-1","quantity":5}`; `…/ABC-1;x/purchase` (quantity 2) → `200 {"skuId":"ABC-1","quantity":3}`; `…/ABC-1%3Blot=7` → 400. No test sends `;` in a skuId.
- Related: #3, PR #11; G11, R7, S2

### C-05 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250810
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/InventoryService.java:75`-81
- Finding: `@Transactional(readOnly = true)` on `find` begins a JPA transaction (borrowing a Hikari connection) before `SkuId.isValid`. G11 says an invalid GET skuId returns 404 without touching the database; the write methods check first.
- Failure scenario: With Postgres down or the pool exhausted, `GET /inventory/-bad` blocks for the Hikari timeout and returns 500 instead of an immediate 404.
- Evidence: Postgres stopped: `GET /inventory/-bad` → 500 "Internal server error" after 30.08 s; `POST /inventory/-bad` → 400 in 0.02 s. Adding `transactionManager` to `verifyNoInteractions` in `InventoryServiceReadTest#findWithInvalidSkuIdReturnsEmptyWithoutRepositoryAccess` fails 5/5 cases.
- Related: #3, PR #11; G11, S2

### C-34 (NIT, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250871
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/web/InventoryController.java:75`
- Finding: Spring's `produces` condition ignores `q=0`, so `Accept: application/json;q=0` (RFC 9110: JSON refused) passes Y1's gate (also line 89).
- Failure scenario: A client that refuses JSON still changes stock.
- Evidence: `curl -X POST localhost:8080/inventory/Q0 -H 'Content-Type: application/json' -H 'Accept: application/json;q=0' -d '{"quantity":1}'` → 200 application/json, ledger row written.
- Related: U2, Y1

### C-13 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250830
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/web/JsonAcceptForGetFilterTest.java:29`-39
- Finding: The PR #11 fix for filter bypass via `;params` and `%69nventory` (JsonAcceptForGetFilter.java:41-49) has no regression test; only literal paths are tested.
- Failure scenario: Reverting `routedPath` to `getRequestURI()` makes `GET /inventory;v=1/x` with `Accept: application/xml` return 406; all 705 tests stay green.
- Evidence: Mutation `return request.getRequestURI();`: existing rows pass. Added rows `/inventory;v=1/x` and `/%69nventory/x` pass at baseline and are the only failures under the mutation.
- Related: PR #11; U2, G10

## Acceptance criteria

- [ ] A raw `;` in the skuId segment never reaches a different SKU: `POST /inventory/ABC-1;lot=7` returns 400 "Invalid request", `POST /inventory/ABC-1;x/purchase` and `GET /inventory/ABC-1;x=y` return 404 "SKU not found"; no ledger or idempotency row is written. Tests fail at baseline and pass after.
- [ ] `GET /inventory/{invalid}` returns 404 without starting a transaction or borrowing a connection: `InventoryServiceReadTest#findWithInvalidSkuIdReturnsEmptyWithoutRepositoryAccess` also verifies no interaction with the transaction manager (fails at baseline).
- [ ] A POST with `Accept: application/json;q=0` returns 400 "Invalid request" and writes nothing (U2/Y1); a test fails at baseline and passes after.
- [ ] JsonAcceptForGetFilterTest covers `/inventory;v=1/x` and `/%69nventory/x`; the rows pass at baseline and fail if `routedPath` is reverted to `getRequestURI()`.


### Current decision

1 `[Decision change]` comment(s) on this issue, oldest first. The latest comment wins for a decision ID that appears more than once.

**New decision C3: raw skuId segment with ";" kept, find checks before its transaction, POST Accept q=0 refused; G11, S2, U2 and Y1 refined** — [2026-09-27T17:25:08Z](https://github.com/kgtech/nuuly-take-home/issues/23#issuecomment-5858082841)

> Was: Controllers passed Spring's @PathVariable, which strips ";" content, so /inventory/ABC-1;lot=7 was handled as ABC-1; InventoryService.find opened a read-only transaction before checking the skuId; a POST with Accept: application/json;q=0 passed the produces condition (which ignores q) and wrote stock.
> Now: Controllers pass the decoded raw skuId segment with any ";" content, so ";" and %3B are both part of the ID (create 400, GET and purchase 404, nothing written or stored). find checks SkuId.isValid before a read-only TransactionTemplate. On POST, the most specific Accept range matching application/json decides (RFC 9110); q=0 refuses JSON, and JsonAcceptForPostInterceptor throws the same HttpMediaTypeNotAcceptableException Y1 uses (400 "Invalid request"). GET still ignores Accept.
> Why: Critique findings C-04, C-05 and C-34 (PR #20): a request for one ID changed another SKU's stock; an invalid GET waited 30 s for a connection with the database down; a client refusing JSON still changed stock.
> Affects: InventoryController, InventoryService, new JsonAcceptForPostInterceptor, tests (InventoryRequestValidationTest, InventoryApiIntegrationTest, IdempotencyApiIntegrationTest, TomcatRejectionIntegrationTest, InventoryServiceReadTest, JsonAcceptForGetFilterTest, new JsonAcceptForPostInterceptorTest), README Assumptions; ai/decision-board.html, DECISIONS.md, CLAUDE.md (commit 35eb8fc).


## #24: C-10, C-19, C-15, C-33: Enforce the append-only ledger in Postgres and share test cleanup

- Labels: critique
- State: OPEN (not started)
- Created: 2026-09-26T15:11:01Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/24

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-10 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250823
- Location: `src/main/resources/db/migration/V1__inventory.sql:1`-14
- Finding: Append-only (G5/V1) is a convention: no trigger, rule or REVOKE prevents UPDATE or DELETE on `inventory_ledger` or `sku`.
- Failure scenario: An ad-hoc or future UPDATE rewrites history; the balance changes silently and can go negative.
- Evidence: As the `inventory` user: `update inventory_ledger set quantity_delta = -999 where sku_id='ABC-1'` → 3 rows; `GET /inventory/ABC-1` → `{"skuId":"ABC-1","quantity":-2997}`.
- Related: G5, V1, D5

### C-19 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250845
- Location: `src/test/java/com/kgtech/inventoryapi/idempotency/IdempotencyWiringTest.java:63`-65
- Finding: Database cleanup is hand-written in 14 test classes with different strategies (table wipe vs per-SKU delete; some omit `idempotency_keys`). No shared helper.
- Failure scenario: A new table with an FK to `sku` requires edits in every class; any missed class fails on `DELETE FROM sku`.
- Evidence: `grep -rln "DELETE FROM" src/test` → 14 files; e.g. InventoryApiIntegrationTest.java:46-47 and InventoryPagingIntegrationTest.java:63-64 leave `idempotency_keys`.
- Related: S11; PR #10

### C-15 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250835
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/LedgerQueryPlanTest.java:59`-66
- Finding: `sumQueryUsesSkuIndex` EXPLAINs a hand-written SUM, not the shipped `SkuRepository.findQuantity` (SkuRepository.java:16-19). The other two tests in the class read production SQL.
- Failure scenario: An edit to `findQuantity` defeats the index; no test notices.
- Evidence: Mutating `findQuantity` to `WHERE l.sku_id || '' = s.sku_id` (same result, no index): LedgerQueryPlanTest and SkuRepositoryTest, 0 failures.
- Related: #2 AC5; W2, D3

### C-33 (NIT CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250869
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/LedgerSchemaTest.java:64`-117
- Finding: Seven LedgerSchemaTest constraint tests and five IdempotencyStoreTest rejection tests (141-190) share one body and differ only in input. Downgraded from MINOR: no change in coverage, count or time.
- Tests: `LedgerSchemaTest#rejects*` (7); `IdempotencyStoreTest#differentHashRejected`, `#differentSkuRejected`, `#differentSkuCaseRejected`, `#differentOperationRejected`, `#olderThan24hRejected`
- Proposed: One `@CsvSource`/`@MethodSource` test per class, as IdempotencySchemaTest already does.
- Coverage kept: Unchanged (cases become rows).
- Saves: ~60 lines.
- Evidence: Identical bodies by inspection.
- Related: #1 AC2; D5, S8, T1

## Acceptance criteria

- [ ] A new Flyway migration makes UPDATE and DELETE on `inventory_ledger` (and DELETE on `sku`) fail in Postgres (e.g. a BEFORE UPDATE OR DELETE trigger). A Testcontainers test that updates a ledger row fails at baseline and passes after (SQLSTATE asserted).
- [ ] Test cleanup uses one shared helper (e.g. TRUNCATE ... RESTART IDENTITY CASCADE, which the trigger does not block) in every Testcontainers class; no class hand-writes `DELETE FROM` for these tables.
- [ ] LedgerQueryPlanTest EXPLAINs the shipped `SkuRepository.findQuantity` SQL (read by reflection, as `pageQueryUsesIndexes` does) and fails if that query stops using `inventory_ledger_sku`.
- [ ] LedgerSchemaTest's single-row constraint tests become one parameterized test with the same cases.
- [ ] Suite green; mutation scores not lower.


## #25: C-16, C-14, C-33: Idempotency key retention (tombstone clean-up) and T1 boundary tests

- Labels: critique
- State: OPEN (not started)
- Created: 2026-09-26T15:11:02Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/25

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-16 (decision challenge, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250838
- Location: `src/main/resources/db/migration/V2__idempotency.sql:1`-13
- Finding: Every keyed request that passes validation stores a permanent row, including 404s for SKUs that don't exist (no FK by design). R9 forbids purging, and T1 depends on the row existing, so reclaiming space changes behaviour. Not documented for operators.
- Failure scenario: Unauthenticated purchases with fresh UUIDs grow the table without bound. If an operator purges rows older than 24h, a retried 25h-old key executes again instead of returning T1's 400.
- Evidence: Keyed `POST /inventory/NEVER-EXISTS/purchase` → 404; row stored with `sku_id = NEVER-EXISTS`, 0 sku rows. No DELETE in src/main.
- Related: #6, PR #14; R9, T1, S8, G10

### C-14 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250834
- Location: `src/test/java/com/kgtech/inventoryapi/idempotency/IdempotencyStoreTest.java:177`-195
- Finding: T1 is pinned at 25h (reject) and 23h59m (replay) only; the `created_at < now() - interval '24 hours'` boundary can drift ~1h, and `<` vs `<=` is unpinned (same in IdempotencyApiIntegrationTest.java:275-296).
- Failure scenario: The TTL is edited to 24h59m; a 24h30m-old key replays instead of 400, and no test fails.
- Evidence: Mutating `interval '24 hours'` to `'24 hours 59 minutes'` in IdempotencyStore.java:31: IdempotencyStoreTest and IdempotencyApiIntegrationTest, 0 failures.
- Related: #6 AC3; T1, R9

### C-33 (NIT CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250869
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/LedgerSchemaTest.java:64`-117
- Finding: Seven LedgerSchemaTest constraint tests and five IdempotencyStoreTest rejection tests (141-190) share one body and differ only in input. Downgraded from MINOR: no change in coverage, count or time.
- Tests: `LedgerSchemaTest#rejects*` (7); `IdempotencyStoreTest#differentHashRejected`, `#differentSkuRejected`, `#differentSkuCaseRejected`, `#differentOperationRejected`, `#olderThan24hRejected`
- Proposed: One `@CsvSource`/`@MethodSource` test per class, as IdempotencySchemaTest already does.
- Coverage kept: Unchanged (cases become rows).
- Saves: ~60 lines.
- Evidence: Identical bodies by inspection.
- Related: #1 AC2; D5, S8, T1

## Acceptance criteria

- [ ] Board change (R9, Y4, G14): keys older than 24h keep their row (so T1 still returns 400) but their stored status, content_type and body are cleared by a documented clean-up. Decision change posted on this issue; DECISIONS.md/CLAUDE.md regenerated.
- [ ] The Y4 CHECK and the T1 lookup accept a tombstoned row; a reused tombstoned key returns 400 "Invalid request". Tests fail at baseline and pass after.
- [ ] T1 boundary tests pin just above and just below 24h (e.g. 24h + 1s rejected, 24h - 1 min replayed) using the database clock, so mutating the interval to 24h59m or `<` to `<=` fails a test.
- [ ] IdempotencyStoreTest's reuse-rejection tests become one parameterized test with the same cases.
- [ ] README has an operations note on retention.


## #26: C-03, C-31, C-32, C-37: Cross-SKU SERIALIZABLE conflicts, concurrency test assertions and retry policy constants

- Labels: critique
- State: OPEN (not started)
- Created: 2026-09-26T15:11:03Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/26

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-03 (decision challenge, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250802
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/InventoryWritesImpl.java:12`-26
- Finding: The SUM predicate's SSI locks (relation lock under Seq Scan, btree page locks under index scan) make writers on unrelated SKUs conflict; each conflict costs a retry. W1/W2 address same-SKU contention only. Downgraded from MAJOR: no exhausted retries observed.
- Failure scenario: 10–16 clients each writing their own SKU on a small ledger incur 0.1–0.5 retries per request; exhaustion (500) needs more concurrency than the default Hikari pool allows.
- Evidence: Throwaway RANDOM_PORT test, one ledger row per SKU, N threads × 10 POSTs on distinct SKUs, MethodRetryEvent counter. Seq Scan plan (after ANALYZE): 1,149 retries across runs, 0 exhausted, 0 × 500. Index plan: 225–237 retries per 1,560 requests, 0 exhausted. No test uses more than one SKU concurrently.
- Related: #2; W1, W2, D4, S11; PR #10 test concern

### C-31 (MINOR, PLAUSIBLE)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250863
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/IdempotencyHttpConcurrencyTest.java:128`-188
- Finding: The same-key concurrency tests pass whether losers take the R2 40001 → retry → replay branch or simply replay after the winner commits; nothing asserts that a retry happened.
- Failure scenario: A broken concurrent-claim branch passes on runs where requests don't overlap.
- Evidence: No MethodRetryEvent assertion in the class; `@RecordApplicationEvents` is already used in StockWriteRejectionEventsTest.
- Related: #6 AC4; R2, W1

### C-32 (MINOR, PLAUSIBLE)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250866
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/InventoryConcurrencyTest.java:55`-82
- Finding: `runTogether` waits in `ExecutorService.close()` and `future.get()` without a timeout; no JUnit default timeout is configured. The shared `Concurrently` helper uses 30 s timeouts.
- Failure scenario: A stuck writer hangs `./gradlew build` instead of failing with the thread that stuck.
- Evidence: InventoryConcurrencyTest.java:59, :75 vs Concurrently.java:41, :48; no junit-platform.properties. Hang not reproduced.
- Related: D9, S11, W2

### C-37 (NIT, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250882
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/InventoryService.java:50`-51
- Finding: The W2 retry policy literals (here and 63-64) and the Idempotency-Key `@Parameter` block (InventoryController.java:82-85, 98-101) are each written twice.
- Failure scenario: A W2 tuning change applied to one method leaves add and purchase with different retry budgets; no test compares them.
- Evidence: Code references above.
- Related: W2, S3

## Acceptance criteria

- [ ] README documents that concurrent writes on different SKUs can conflict under SERIALIZABLE while the ledger is small and are absorbed by the W2 retries (W1/W2 unchanged).
- [ ] A concurrency test drives writes on distinct SKUs (≤ 8 threads per SKU, per W2) and asserts no 500s and correct balances.
- [ ] The same-key HTTP concurrency tests assert that the R2 concurrent-claim path ran (at least one non-aborted MethodRetryEvent), or state explicitly why a run may not overlap.
- [ ] InventoryConcurrencyTest's helper uses bounded waits (as `Concurrently` does), so a stuck writer fails the test instead of hanging the build.
- [ ] The W2 retry policy values are defined once and shared by both `@Retryable` annotations; the Idempotency-Key `@Parameter` description is defined once. No behaviour change.


## #27: C-11, C-12, C-39: Failure logging, Postgres port binding, and container runtime settings

- Labels: critique
- State: OPEN (not started)
- Created: 2026-09-26T15:11:04Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/27

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-11 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250824
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/StockWriteFailureLogger.java:29`-33
- Finding: After the listener logs (WARN without stack for non-retryable, ERROR with stack on exhaustion), the exception reaches the advice catch-all (InventoryErrorAdvice.java:54-61), which logs ERROR with the full stack again. This undoes the issue #2 [Decision change] "WARN, no stack trace" at the HTTP layer.
- Failure scenario: A 55P03 lock timeout pages ERROR-based alerting; each exhausted retry logs two stack traces.
- Evidence: Baseline test output TEST-…IdempotencyApiIntegrationTest.xml:49-50: `WARN … Stock write failed without retry for SKU widget (SQLState P0001)` followed by `ERROR … InventoryErrorAdvice : Unhandled exception on POST /inventory/widget` and the UncategorizedSQLException stack. `StockWriteRetryTest#lockNotAvailableIsNotRetried` asserts at service level only.
- Related: #2, #3; W2, S6

### C-12 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250828
- Location: `compose.yaml:10`-11
- Finding: Postgres is published on 0.0.0.0 with committed credentials inventory/inventory. `docker compose up --build` doesn't need a host port; bootRun works with a loopback binding.
- Failure scenario: On a shared network, anyone can connect as the schema owner and modify the ledger directly (see C-10).
- Evidence: `docker port <postgres container>` → `5432/tcp -> 0.0.0.0:33506`. The PR #13 thread was declined without a rationale.
- Related: #5, PR #13; D8, S4

### C-39 (NIT, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250887
- Location: `Dockerfile:19`
- Finding: The runtime image sets no heap flags (ergonomic 25% of container memory), and the compose app service (compose.override.yaml:3-13) has no healthcheck although readiness exists.
- Failure scenario: Under a container memory limit the heap shrinks accordingly; `docker compose ps` reports the app "Up" while it is still migrating.
- Evidence: `java -XX:+PrintFlagsFinal` in the app container → `MaxHeapSize = 2099249152 {ergonomic}`; `docker compose ps` → `app Up 3 minutes` (no health), `postgres Up (healthy)`.
- Related: #5, PR #13; D8, D10

## Acceptance criteria

- [ ] A failed stock write produces the listener's single log entry only: non-retryable → one WARN without stack trace; exhausted → one ERROR with stack trace. The advice does not log the same exception again. A test capturing logs through the HTTP path fails at baseline and passes after.
- [ ] compose.yaml publishes Postgres on 127.0.0.1 only (or not at all for the reviewer path) while bootRun's Docker Compose support still works; ComposeFilesTest asserts it.
- [ ] The runtime image sets an explicit heap policy (e.g. `-XX:MaxRAMPercentage`), and compose.override.yaml gives the app a healthcheck on `/actuator/health/readiness`; README's run instructions updated. `docker compose up --build` smoke shows the app healthy.


## #28: C-17, C-18, C-35, C-36, C-38: Board and docs consistency, single outcome-to-response mapping

- Labels: critique
- State: OPEN (not started)
- Created: 2026-09-26T15:11:05Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/28

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-17 (decision challenge, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250839
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/WriteResult.java:6`-15
- Finding: The idempotency interceptor must return the advised method's type, so the domain `WriteResult` carries `Stored(StoredResponse)` (HTTP status, Content-Type, body) and `InvalidRequest`. The same service call returns `NotFound` unkeyed and a rendered 404 when keyed; JSON is serialized inside the SERIALIZABLE transaction (OutcomeResponses.java:43-53).
- Failure scenario: A non-HTTP caller (consumer, batch job) passing a key receives HTTP artefacts instead of domain outcomes.
- Evidence: WriteResult.java:9,13; IdempotentResults.java:6-19; IdempotencyInterceptor.java:40-57. `IdempotencyInterceptorTest#executedMapsToStoredWithSkuIdPassedToToStored` pins the behaviour.
- Related: #15, PR #14, PR #16; Z1, Z2, Y4

### C-18 (MINOR, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250841
- Location: `CLAUDE.md:103`
- Finding: Z2's web-package class list omits `StoredResponses` (added in PR #16); DECISIONS.md carries the same text. Fix is a board change (S9).
- Failure scenario: An agent auditing Z2 against the list moves or flags the class.
- Evidence: `grep -c StoredResponses CLAUDE.md DECISIONS.md` → 0, 0; src/main/java/com/kgtech/inventoryapi/inventory/web/StoredResponses.java exists.
- Related: #15, PR #16; Z2, S9

### C-35 (NIT, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250876
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/web/StoredResponses.java:14`-18
- Finding: Replayed errors take Content-Type from the stored row instead of the TextErrors helper. Y4 requires that, but S5/D6 (CLAUDE.md:37, 65: "one helper for every error response") were not refined to say so.
- Failure scenario: A contributor following S5 routes replays through TextErrors and breaks Y4, or flags StoredResponses as an S5 violation.
- Evidence: StoredResponses.java:16 `.header(HttpHeaders.CONTENT_TYPE, response.contentType())`.
- Related: S5, D6, Y4, S9

### C-36 (NIT, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250878
- Location: `src/main/java/com/kgtech/inventoryapi/inventory/web/InventoryController.java:150`-158
- Finding: Two sealed switches map the same outcomes: `toResponse` here and `OutcomeResponses.toStored` (OutcomeResponses.java:43-53); the 200 body is produced once by the converter and once by a hand-called JsonMapper.
- Failure scenario: A change to the 200 body is made in one place only; `OutcomeResponsesTest#toStoredMatchesUnkeyedResponse` is the only guard.
- Evidence: Code references above.
- Related: S5, Y4

### C-38 (NIT, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250886
- Location: `src/main/java/com/kgtech/inventoryapi/idempotency/Operation.java:3`
- Finding: Production comments cite story-local IDs ("plan OQ3" here, "OQ2 (#8)" in application.yaml:19) that restart per story plan and appear in no committed decision record.
- Failure scenario: A reader can't resolve the rationale from DECISIONS.md or CLAUDE.md.
- Evidence: `grep -rn OQ3 *.md ai docs` → agent-prompts.md only.
- Related: D0, S9

## Acceptance criteria

- [ ] Board changes, regenerated DECISIONS.md/CLAUDE.md: Z2 lists `StoredResponses` and states that `StoredResponse` is an opaque replay token carried through `WriteResult`; S5/D6 are refined so replayed responses keep their stored Content-Type (Y4).
- [ ] Outcomes are mapped to responses in one place: the controller renders every outcome through the same mapping used for stored responses (or an equivalent single mapping), with byte-identical unkeyed and keyed responses. Any required S5/D6 change is proposed first.
- [ ] Production comments no longer cite story-local OQ IDs; they state the rationale or a decision ID.
- [ ] Existing contract tests unchanged and green; mutation scores not lower.


## #29: C-20, C-21, C-22, C-30: Test suite — one Postgres container and fewer Spring contexts

- Labels: critique
- State: OPEN (not started)
- Created: 2026-09-26T15:11:06Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/29

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-20 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250846
- Location: `src/test/java/com/kgtech/inventoryapi/TestcontainersConfiguration.java:15`-23
- Finding: `postgresContainer()` is an instance `@Bean`, so each of the six Spring context cache keys starts its own Postgres container.
- Tests: All 21 Testcontainers classes
- Proposed: One JVM-wide static container returned by `@Bean(destroyMethod = "") @ServiceConnection`.
- Coverage kept: All; 705/705 green with the change applied.
- Saves: 0 tests; ~8.5 s. `./gradlew test --rerun-tasks` wall 24.1–24.8 s → 15.1–16.1 s (suite 20.9–21.6 s → 13.0–13.4 s), 6 container starts → 1.
- Evidence: Change applied in a disposable worktree and timed twice before and after.
- Related: D9; PR #10, PR #9

### C-21 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250849
- Location: `src/test/java/com/kgtech/inventoryapi/InventoryApplicationTests.java:19`-20
- Finding: Testcontainers classes use three annotation sets (plain, +MockMvc, RANDOM_PORT) that build separate contexts with the same beans; sharing relies on copying annotations by hand (see ActuatorHealthTest's Javadoc).
- Tests: 18 Testcontainers classes (e.g. InventoryApplicationTests, ActuatorHealthTest, InventoryHttpConcurrencyTest)
- Proposed: One meta-annotation (`@SpringBootTest(RANDOM_PORT)` + `@AutoConfigureMockMvc` + `@Import(TestcontainersConfiguration)`); ActuatorHealthDownTest, IdempotencyWiringTest and StockWriteRetryFilterTest keep their own contexts.
- Coverage kept: All; 705/705 green.
- Saves: Contexts 6 → 4. On top of C-20 the time saving is within noise (14.6–15.9 s wall); main value is preventing accidental context splits.
- Evidence: Change applied on top of C-20 in a disposable worktree.
- Related: D9

### C-22 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250852
- Location: `src/test/java/com/kgtech/inventoryapi/idempotency/IdempotencyWiringTest.java:125`-167
- Finding: `@MockitoSpyBean IdempotencyStore` forces a separate context, and three of the four tests duplicate end-to-end tests in IdempotencyApiIntegrationTest.
- Tests: `IdempotencyWiringTest#retryWrapsClaim`, `#unkeyedPostNeverCallsStore`, `#keyedPostCallsStoreOnce`
- Proposed: Delete the three; move `retryAdvisorWrapsIdempotencyAdvisor` into IdempotencyApiIntegrationTest.
- Coverage kept: Z1 advisor order: the moved test. Keyed 40001 retries the claim: `IdempotencyApiIntegrationTest#forced40001RetriesClaimInNewTransaction`, `#forced40001AtCommitRetriesKeyedWrite`. G8: `#unkeyedRequestsNeverTouchIdempotencyTable`.
- Saves: 3 tests and one context (2.43 s at baseline, 0.63 s after C-20).
- Evidence: PIT (Testcontainers scope): the three tests kill 60 mutants, 0 uniquely.
- Related: Z1, X1, W2, R2, G8

### C-30 (MINOR CONSOLIDATE, PLAUSIBLE)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250861
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/StockWriteRetryFilterTest.java:25`-30
- Finding: A class-level `@MockitoBean SkuRepository` gives this class its own Boot context and Postgres; neither test touches the database.
- Tests: Both tests in StockWriteRetryFilterTest
- Proposed: Move to a `@SpringJUnitConfig` with `InventoryService`, `ResilienceConfiguration` and mocked `SkuRepository`/`PlatformTransactionManager` (as InventoryServiceReadTest.Config).
- Coverage kept: Y2 through the real `@Retryable` proxy; `SerializationFailureTest`; `StockWriteRetryTest` real 40001/40P01/55P03.
- Saves: 0 tests; context start 1.91 s at baseline, 0.32 s after C-20.
- Evidence: Report timings: suite 1.934 s vs test sum 0.017 s. Change not applied.
- Related: Y2; PR #10

## Acceptance criteria

- [ ] Test-only. One JVM-wide Postgres container serves every Testcontainers context.
- [ ] Testcontainers classes share one meta-annotation; only ActuatorHealthDownTest (and any class that must) keeps its own context.
- [ ] IdempotencyWiringTest's three duplicate tests are removed and `retryAdvisorWrapsIdempotencyAdvisor` moves to IdempotencyApiIntegrationTest; StockWriteRetryFilterTest runs without Boot or Postgres.
- [ ] Every acceptance criterion and decision under "Coverage kept" still traces to a named test; mutation scores for the scoped classes not lower; suite green. PR body reports test count, runtime per layer and mutation score before and after.


## #30: C-23, C-24, C-25, C-26, C-27, C-28: Test suite — remove duplicate validation matrices and redundant cases

- Labels: critique
- State: OPEN (not started)
- Created: 2026-09-26T15:11:07Z
- URL: https://github.com/kgtech/nuuly-take-home/issues/30

### Body (verbatim)

Fix for findings from the codebase critique, [kgtech/nuuly-take-home#20](https://github.com/kgtech/nuuly-take-home/pull/20).

Baseline for the critique: `211bd95`. Mutation baseline (PIT 1.30.0, default mutators; scopes listed in the critique PR general comment): no-DB scope 113/129 (87.6%), Testcontainers scope 123/128 (96.1%). Fixes must not lower either.

## Findings

### C-23 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250853
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/SkuIdTest.java:46`-56
- Finding: One regex with two outcomes (`SkuId.rejection`) is re-run with the same inputs in ~120 cases across six classes.
- Tests: `SkuIdTest#rejectionOnCreateIsInvalidRequest` ×10, `#rejectionOnPurchaseIsNotFound` ×10; `OutcomeResponsesTest#beforeClaim*` ×14; `InventoryServiceReadTest#writeWithMalformedSkuId…` ×15; `InventoryServiceTest#malformedSkuIdReturnsOutcomeAndWritesNothing` ×4; `InventoryRequestValidationTest#*PassesInvalidSkuId*` ×9; `IdempotencyHeaderOrderTest#rawKeyAndSkuIdPassedThrough` ×30
- Proposed: SkuIdTest: one parameterized test per id asserting `isValid` and both rejections. OutcomeResponsesTest ×14 → ×1 (keep a create rejection). InventoryServiceReadTest ×15 → ×5 (drop the key column). InventoryServiceTest ×4 → delete. InventoryRequestValidationTest ×9 → ×3. IdempotencyHeaderOrderTest ×30 → ×2.
- Coverage kept: G11/S2/U3: SkuIdTest, InventoryServiceReadTest, `IdempotencyApiIntegrationTest#validKeyBadSkuIdOnCreate400`, `#validKeyBadSkuIdOnPurchase404`, `#invalidSkuIdNotStored`, StockWriteRejectionEventsTest skuId cases.
- Saves: ~80 tests, ~60 ms; mainly maintenance.
- Evidence: PIT (both scopes): removed rows kill 18 mutants, 0 uniquely, provided the kept `beforeClaim` row is a create rejection (otherwise `OutcomeResponses.beforeClaim:38` EmptyObjectReturnVals survives).
- Related: G11, R7, S2, U3, Z1

### C-24 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250854
- Location: `src/test/java/com/kgtech/inventoryapi/idempotency/IdempotencyKeyTest.java:37`-61
- Finding: The Idempotency-Key format matrix is repeated at unit, interceptor, HTTP (Testcontainers) and event layers; `parse*` tests repeat the `isValid` inputs.
- Tests: `IdempotencyKeyTest#parseRejectsInvalid` ×14, `#parseReturnsSameUuidRegardlessOfCase` ×4; `IdempotencyInterceptorTest` key matrix ×7; `IdempotencyApiIntegrationTest#malformedKeyReturns400AndStoresNothing` ×12; `StockWriteRejectionEventsTest` key rows ×3
- Proposed: IdempotencyKeyTest: one valid-key and one invalid-key test (36 → 18). Interceptor ×7 → ×1. HTTP ×12 → ×4 (`""` and `abc` per POST). Events ×3 → ×1.
- Coverage kept: S3, #6 AC6, #15 AC3: IdempotencyKeyTest merged tests and the four kept HTTP rows.
- Saves: ~33 tests, incl. ~50 ms of Testcontainers time.
- Evidence: PIT: removed rows kill 15 mutants, 0 uniquely.
- Related: S3, U3, #6 AC6, #15 AC3

### C-25 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250855
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/InventoryPagingIntegrationTest.java:231`-233
- Finding: Each case pays an 11-request `seedMixed()` to re-check logic pinned in unit and slice tests. CONFIRMED for the Java-logic rows; PLAUSIBLE for `afterUsesCCollation` and `afterWithNulReturns200` (Postgres behaviour PIT can't mutate).
- Tests: `lenientLimitAlwaysReturns200` ×11, `afterUsesCCollation` ×5, `afterWithNulReturns200` ×4, `repeatedAfterReturns400` ×3, `pagedGetIgnoresXmlAccept`, comma-cursor tests ×4, `walkFollowingLinkVisitsEverySkuOnce`
- Proposed: `lenientLimitAlwaysReturns200` → {0, -1, abc}; delete `afterUsesCCollation`, `repeatedAfterReturns400`, `pagedGetIgnoresXmlAccept`; `afterWithNulReturns200` and comma-cursor → ×1; walk limits → {2, 6, 7, 8}; seed by SQL except in `pageQuantitiesMatchLedgerSum`.
- Coverage kept: #7 AC1–AC4, R4, R8, Z3: kept rows plus `InventoryServiceReadTest#listIgnoresUnusableLimit`, `#listTruncatesAfterAtNul`, `InventoryListPagingTest`, `InventoryListMalformedQueryIntegrationTest#repeatedAfterReturns400`. C collation: `SkuRepositoryTest#findQuantitiesAfterPagesInCCollationOrder`, `LedgerSchemaTest#skuIdOrdersByCCollation`.
- Saves: ~26 tests, ~0.5 s.
- Evidence: PIT (Testcontainers scope): removed rows kill 49 mutants, 0 uniquely.
- Related: #7; G9, R4, R8, Z3, U2

### C-26 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250857
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/StockWriteRetryTest.java:216`-237
- Finding: `keyedExhaustedRetriesLogSku` runs all 11 attempts with real backoff a second time; the logger reads the SKU identically with or without a key.
- Tests: `StockWriteRetryTest#keyedExhaustedRetriesLogSku`
- Proposed: Delete; keep `exhaustedRetriesPropagateAndLogSku`.
- Coverage kept: #2 AC8/W2: `exhaustedRetriesPropagateAndLogSku`. Retry wraps claim: `IdempotencyApiIntegrationTest#forced40001RetriesClaimInNewTransaction`. No key row on failure: `#nonRetryableFailureStoresNothing`.
- Saves: 1 test, 0.65 s (second-slowest test).
- Evidence: PIT: kills 26 mutants, 0 uniquely.
- Related: #2 AC8; W2, Z1

### C-27 (MINOR CONSOLIDATE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250858
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/web/IdempotencyHeaderOrderTest.java:168`-192
- Finding: `storedResultRenderedUnchanged` crosses 2 operations × 4 stored shapes for a single `case Stored` branch in the controller.
- Tests: `IdempotencyHeaderOrderTest#storedResultRenderedUnchanged` ×8
- Proposed: ×8 → ×1 (one text/plain stored error); keep `stored200HasSameContentTypeAsUnkeyed200`.
- Coverage kept: Y4, #15 AC2: `StoredResponsesTest#rendersStoredResponseUnchanged` ×5, `IdempotencyApiIntegrationTest#replayed200IsJsonAndErrorsAreTextPlain`.
- Saves: 7 tests, ~110 ms.
- Evidence: PIT: removed rows kill 4 mutants, 0 uniquely.
- Related: Y4, #15 AC2

### C-28 (MINOR NOISE, CONFIRMED)
- Critique thread: https://github.com/kgtech/nuuly-take-home/pull/20#discussion_r4110250860
- Location: `src/test/java/com/kgtech/inventoryapi/inventory/ApiDocsTest.java:108`-131
- Finding: Two tests are implied by others, and the two-fetch half of `exportIsStable` (414-419) serializes the same cached springdoc model twice in one JVM. Separately, nothing pins `@Hidden` on the catch-all (PR #11 concern). CONFIRMED for the two tests; PLAUSIBLE for `exportIsStable`.
- Tests: `ApiDocsTest#errorResponsesAreTextPlainAndSuccessIsJson`, `#catchAllIsNotAddedToOperations` (132-143), two-fetch half of `#exportIsStable`
- Proposed: Delete the two tests; keep only the sorted-keys half of `exportIsStable`. Add a test that fails when `@Hidden` is removed.
- Coverage kept: D7/S5/S12: `exportedErrorResponsesAreTextPlain`, `exportedYamlMatchesServedJson`, `eachOperationListsExactlyItsSpecCodes`, `openApiYamlIsRegeneratedAndCommitted`.
- Saves: 2 tests.
- Evidence: Hand mutations (PIT can't mutate annotations): GET 404 mediaType → JSON fails the deleted test and two kept tests together. Removing `@Hidden` and/or setting `override-with-generic-response: true` fails no ApiDocsTest.
- Related: D7, S5, S6, S12

## Acceptance criteria

- [ ] Test-only. The listed tests are merged or removed as proposed in each finding (keeping a create-rejection row in OutcomeResponsesTest's `beforeClaim`).
- [ ] ApiDocsTest gains a test that fails when `@Hidden` is removed from the catch-all handler.
- [ ] Every acceptance criterion and decision under "Coverage kept" still traces to a named test; mutation scores for the scoped classes not lower; suite green. PR body reports test count, runtime per layer and mutation score before and after.

