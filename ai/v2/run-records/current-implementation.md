# The current implementation on `main` (317ab0c)

Read before designing V2. File references are to `main` **at 317ab0c**, the SHA the package was built from. `main` has since merged PR #34 (8c3c2b4), which fixes C-05 (the id check now runs before the read-only transaction) and parts of #23; the statements below describe 317ab0c.

## Stock changes
- Stock is never stored as a number. `inventory_ledger` (`src/main/resources/db/migration/V1__inventory.sql`) holds one row per change (`quantity_delta <> 0`, `reason IN ('add','purchase')`), and a balance is `SUM(quantity_delta)` per `sku_id` (V1, D3). `sku` holds only the id (COLLATE "C", varchar(64)).
- Add: `INSERT INTO sku ... ON CONFLICT DO NOTHING`, then `INSERT INTO inventory_ledger ... SELECT ... WHERE SUM <= 2^63-1 - :q RETURNING SUM + :q` (`InventoryWritesImpl.ADD`). No row → `Overflow` → 400 "Invalid request" (G12, U1).
- Purchase: `INSERT ... SELECT -:q WHERE EXISTS sku AND SUM >= :q RETURNING SUM - :q` (`InventoryWritesImpl.PURCHASE`). No row → a second SELECT decides `NotFound` vs `Insufficient`.
- Guarantees: the WHERE and the SUM run in one SERIALIZABLE transaction, so no oversell and no overflow (G7, W1). Outcomes are values, never exceptions (R1).
- Weak points: every write and every read recomputes `SUM` over the SKU's ledger rows (index `inventory_ledger_sku`), so cost grows with history; nothing in Postgres stops `UPDATE`/`DELETE` on the ledger (critique C-10, issue #24 open); a SERIALIZABLE `SUM` predicate makes writers on *different* SKUs conflict (C-03, issue #26 open).

## Concurrency
- Every stock write runs in a new SERIALIZABLE transaction (`InventoryService` TransactionTemplate, X1) and is wrapped by `@Retryable` on `PessimisticLockingFailureException` whose root SQLState is 40001/40P01 (`SerializationFailure`, Y2, W2): 10 retries, 5–200 ms jittered backoff. Exhausted → 500 and an ERROR log (`StockWriteFailureLogger`).
- `requireNoWeakerTransaction()` refuses to join a non-SERIALIZABLE transaction (Z1).
- Guarantee: exactly M of N concurrent purchases succeed for stock M (`InventoryConcurrencyTest`, `InventoryHttpConcurrencyTest`); adds sum up.
- Weak points: retries are the correctness mechanism; the false-conflict rate on unrelated SKUs is 0.1–0.5 retries per request at 10–16 clients (C-03).

## Idempotency
- Optional `Idempotency-Key` (UUID, S3) on both POSTs. Handled as cross-cutting advice (`idempotency/IdempotencyInterceptor`, a programmatic spring-aop advisor on `@Idempotent` service methods, Z1). Proxy order [Retry, Idempotency, Tx].
- Flow (`IdempotencyStore.execute`, R2, S8, Y4, T1): in a SERIALIZABLE REQUIRES_NEW transaction, `INSERT ... ON CONFLICT DO NOTHING RETURNING` claims the key with operation, skuId and a SHA-256 of (operation, skuId, quantity) (Y3). Claimed → run the write → `UPDATE` status/content_type/body in the same transaction (CHECK: all NULL or all set). Not claimed → SELECT the row: expired (>24h by DB clock) or different operation/skuId/hash → 400 "Invalid request"; else replay the stored status, Content-Type and body unchanged (`web/StoredResponses`).
- Guarantee: a concurrent second claim raises 40001 under SERIALIZABLE and the retry replays (`IdempotencyHttpConcurrencyTest`).
- Weak points: rows are never purged (C-16, issue #25 open); a 24h-old key returns 400 rather than re-executing (T1, deliberate); replays always hit Postgres.

## Reads and pagination
- `GET /inventory/{skuId}`: `SkuId.isValid` first (no DB for a malformed id), then `SkuRepository.findQuantity` (native `SUM` cast to bigint) in a `@Transactional(readOnly = true)` (D3). C-05 (issue #23, PR #34 open): the transaction is opened before the id check.
- `GET /inventory`: keyset page `WHERE sku_id > :after ORDER BY sku_id LIMIT :limit+1`, page size `limit` (1–250) or 250 by default, `Link: <...>; rel="next"` built from the routed path (G9, R4, R8, C2). Repeated `after` → 400 (Z3). Every page recomputes a `SUM` per row.
- Reads run at READ COMMITTED and never wait on a writer's row lock; they see the last committed SUM. Nothing is cached.

## Web layer (kept as is in V2)
- `inventory/web`: hand-written controller with springdoc annotations (D7, C2), `InventoryErrorAdvice` (text/plain contract on every path but `/actuator/**` and springdoc, S6, C1), `JsonAcceptForGetFilter` (U2), `TextErrors` (G6, S5), `ServletContainerConfiguration` + `TextErrorReportValve` (Tomcat rejections are text/plain; `%2F` passes through; TRACE → 405, C1), `OutcomeResponses` (how outcomes are stored and rebuilt for a key, Y4), `StoredResponses`.
- Jackson: no scalar coercion, unknown properties ignored (G13). `@Valid` body first (G4, U3).

## Tests (what they cover)
- 788 tests in 40 classes; Testcontainers Postgres via `TestcontainersConfiguration` (`@ServiceConnection`, image from a Gradle system property); `RawHttp` sends byte-exact requests to a RANDOM_PORT server; `Concurrently` releases N threads together. The suite starts 6 containers and 7 Spring contexts (C-20..C-22, issue #29 open) and repeats validation matrices at several layers (C-23..C-28, issue #30 open).

## Operations
- `compose.yaml` (Postgres 18, pg_isready healthcheck) + `compose.override.yaml` (app, port 8080); Dockerfile builds a layered jar. Postgres is published on all interfaces with a committed password (C-12, issue #27 open); no CI workflow exists (0 of 13 PRs had checks).
