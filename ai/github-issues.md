# GitHub issues: Nuuly Inventory API

Eight stories, one per step of the build order in `ai/decision-review.md`, which T6 requires. They reflect all 62 decisions in `DECISIONS.md` after round 9: stock is `SUM(inventory_ledger.quantity_delta)` (V1-C), written in SERIALIZABLE transactions that retry on serialization failure (W1-B, D4-D, W2-A), with the retry around a TransactionTemplate (X1-B) and filtered to serialization failures by a retry predicate (Y2-A).

- Story 4 completes the spec. Story 5 makes the repo submittable.
- Stories 6 (idempotency) and 7 (paging) are the first to move to "Designed, not built" at the hour-20 stop (T6).

Each story is one issue. The title is the `###` heading, and everything under it is the issue body.

---

### 1. Project setup and Flyway schema for the ledger

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
- [ ] Outside docs (`*.md`, `ai/`, `docs/`) and the generated wrapper scripts (`gradlew`, `gradlew.bat`), the only files that contain version numbers are `libs.versions.toml` and `gradle-wrapper.properties`.

---

### 2. SERIALIZABLE ledger add and purchase, with retries

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

### 3. The four spec operations, text/plain errors and contract tests

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

### 4. Concurrency tests

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

### 5. Docker Compose, health checks and a clean-clone run

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

### 6. Optional Idempotency-Key on both POSTs

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

### 7. Opt-in keyset paging for GET /inventory

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

### 8. OpenAPI export, final README and agent-prompts.md

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
