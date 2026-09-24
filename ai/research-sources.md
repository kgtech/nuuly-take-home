# Research sources

Sources behind the decision board (checked 2026-09-23). "Unverified" claims include the test that would prove them; run those tests during the build.

| Claim | Status | Decisions | Test to prove |
|---|---|---|---|
| [PostgreSQL 18 docs: Read Committed. A waiting UPDATE re-evaluates its WHERE clause against the updated row version](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED) | Verified | G7, D4 |  |
| [PostgreSQL docs: row-level locks (FOR UPDATE / FOR NO KEY UPDATE)](https://www.postgresql.org/docs/current/explicit-locking.html#LOCKING-ROWS) | Verified | D4 |  |
| [Hibernate 7.1 PostgreSQLDialect: PESSIMISTIC_WRITE emits "for no key update", not FOR UPDATE](https://github.com/hibernate/hibernate-orm/blob/7.1/hibernate-core/src/main/java/org/hibernate/dialect/PostgreSQLDialect.java) | Verified | D4 | Confirmed from source only. Turn on spring.jpa.show-sql and assert the @Lock(PESSIMISTIC_WRITE) finder logs "for no key update". |
| [Hibernate 7.1 PostgreSQLLockingSupport: lock.timeout 0 → NOWAIT, -2 → SKIP LOCKED, other → set local lock_timeout](https://github.com/hibernate/hibernate-orm/blob/7.1/hibernate-core/src/main/java/org/hibernate/dialect/lock/internal/PostgreSQLLockingSupport.java) | Verified | D4 |  |
| [Spring Data JPA reference: @Lock on repository methods](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html) | Verified | D4 |  |
| [Spring Framework 7 reference: built-in @Retryable / RetryTemplate (core resilience)](https://docs.spring.io/spring-framework/reference/core/resilience.html) | Verified | D4 |  |
| [Spring Framework 7.0 release notes (Spring Retry folded into spring-core)](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes) | Verified | D4 |  |
| [PostgreSQL docs: serialization failures (SQLSTATE 40001) must be retried by the app; no automatic retry](https://www.postgresql.org/docs/current/mvcc-serialization-failure-handling.html) | Verified | D4 |  |
| [PostgreSQL docs: INSERT … ON CONFLICT DO UPDATE "guarantees an atomic INSERT or UPDATE outcome"](https://www.postgresql.org/docs/current/sql-insert.html) | Verified | G12, D4 |  |
| [PostgreSQL docs: a second inserter of the same unique key waits for the first transaction to commit or roll back](https://www.postgresql.org/docs/current/index-unique-checks.html) | Verified | G14 |  |
| [PostgreSQL error codes: 22003 numeric_value_out_of_range, 23514 check_violation](https://www.postgresql.org/docs/current/errcodes-appendix.html) | Verified | G2, G12 |  |
| [Hibernate SQLStateConversionDelegate: class 22 → DataException (Spring surfaces DataIntegrityViolationException, same as a CHECK violation)](https://github.com/hibernate/hibernate-orm/blob/main/hibernate-core/src/main/java/org/hibernate/exception/internal/SQLStateConversionDelegate.java) | Verified | G12 | From source code. Set a SKU to 2147483647, add 1, and assert the exception type and root SQLState 22003. |
| [Spring Data JPA @Modifying javadoc (clearAutomatically / flushAutomatically default false)](https://docs.spring.io/spring-data/jpa/docs/current/api/org/springframework/data/jpa/repository/Modifying.html) | Verified | D3 |  |
| [spring-data-jpa #2270: native UPDATE … RETURNING runs from @Query and returns rows (issue report, not documented)](https://github.com/spring-projects/spring-data-jpa/issues/2270) | **Unverified** | D3, D4 | Testcontainers test: native @Query "UPDATE inventory SET quantity = quantity - :q WHERE sku_id = :id AND quantity >= :q RETURNING quantity" returning Optional<Integer>; assert the value, then re-read in a new transaction. |
| [Shopify changelog: changeFromQuantity (compare-and-swap) required on inventory mutations from API 2026-04](https://shopify.dev/changelog/making-changefromquantity-field-required) | Verified | D4 |  |
| [Shopify changelog: compare-and-swap for inventory mutations with changeFromQuantity](https://shopify.dev/changelog/compare-and-swap-for-inventory-mutations-with-change-from-quantity) | Verified | D4 |  |
| [Saleor warehouse/management.py: select_for_update on stock rows, then F() increments](https://github.com/saleor/saleor/blob/main/saleor/warehouse/management.py) | Verified | D4 |  |
| [Saleor #543: overselling from concurrent checkouts](https://github.com/saleor/saleor/issues/543) | Verified | G7 |  |
| [Solidus StockItem#adjust_count_on_hand wraps read-modify-write in with_lock (SELECT FOR UPDATE)](https://github.com/solidusio/solidus/blob/main/core/app/models/spree/stock_item.rb) | Verified | D4 |  |
| [Medusa #16576 (open): lost increments in adjustInventory from a read-modify-write race](https://github.com/medusajs/medusa/issues/16576) | Verified | G7 |  |
| [Medusa PR #16575: fixed the reserved_quantity race with FOR UPDATE](https://github.com/medusajs/medusa/pull/16575) | Verified | D4 |  |
| [Vlad Mihalcea: lost update phenomenon, pessimistic vs optimistic fixes](https://vladmihalcea.com/a-beginners-guide-to-database-locking-and-the-lost-update-phenomena/) | Verified | G7, D4 |  |
| [IETF draft-ietf-httpapi-idempotency-key-header-07 (expired draft, not an RFC): header optional, 409 in-flight, 422 key reuse with different payload](https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/) | Verified | G8, G14 |  |
| [Stripe: idempotent requests (keys ≤255 chars, pruned after 24h, errors if params differ)](https://docs.stripe.com/api/idempotent_requests) | Verified | G8, G14 |  |
| [Brandur Leach: implementing Stripe-like idempotency keys in Postgres](https://brandur.org/idempotency-keys) | Verified | G14 |  |
| [Spring blog: Spring Boot 4.1.1 available (2026-08-20)](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/) | Verified | D1 |  |
| [Spring Boot system requirements: Java 17+, compatible up to Java 26; Gradle 8.14+ / 9.x](https://docs.spring.io/spring-boot/system-requirements.html) | Verified | D1, D2 |  |
| [Spring Boot 4.0 migration guide: starter-webmvc, starter-flyway, Jackson 3 (tools.jackson), @AutoConfigureMockMvc](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide) | Verified | D1, D5 |  |
| [Spring Boot 4.1 release notes (Flyway 12.4, spring.jackson.* feature properties)](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes) | Verified | D5 |  |
| [Gradle compatibility matrix: Java 25 needs Gradle 9.1.0+](https://docs.gradle.org/current/userguide/compatibility.html) | Verified | D1, D2 |  |
| [Spring Boot how-to: database initialization with Flyway; ddl-auto defaults to none for non-embedded DBs](https://docs.spring.io/spring-boot/how-to/data-initialization.html) | Verified | D5 |  |
| [Flyway #3780: PostgreSQL needs flyway-database-postgresql since Flyway 10](https://github.com/flyway/flyway/issues/3780) | Verified | D5 |  |
| [Testcontainers 2.0.0 release: artifacts renamed testcontainers-postgresql etc., JUnit 4 removed](https://github.com/testcontainers/testcontainers-java/releases/tag/2.0.0) | Verified | D9 |  |
| [Spring Boot reference: Testcontainers with @ServiceConnection](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html) | Verified | D9 |  |
| [Spring Boot reference: Docker Compose dev services (runs compose.yaml on startup, skipped in tests by default)](https://docs.spring.io/spring-boot/reference/features/dev-services.html) | Verified | D8 |  |
| [springdoc-openapi: 3.x line for Spring Boot 4 (3.1.x supports 4.1); Swagger UI at /swagger-ui.html](https://springdoc.org/) | Verified | D7 |  |
| [openapi-generator PR #22854: useSpringBoot4 / useJackson3 added in 7.20.0](https://github.com/OpenAPITools/openapi-generator/pull/22854) | Verified | D7 |  |
| [openapi-generator #23289: useJackson3 threw IllegalArgumentException on Boot 4 in 7.20.0 (closed; fix version not confirmed)](https://github.com/OpenAPITools/openapi-generator/issues/23289) | **Unverified** | D7 | Run the Gradle plugin 7.25.0 with useSpringBoot4, useJackson3, interfaceOnly, openApiNullable=false; expect clean generation with tools.jackson imports. |
| [openapi-generator #24975 (open): SB4 pom templates pin jackson-databind-nullable 0.2.8 (affects generated poms only)](https://github.com/OpenAPITools/openapi-generator/issues/24975) | Verified | D7 |  |
| [openapi-generator spring generator options (useBeanValidation, interfaceOnly)](https://openapi-generator.tech/docs/generators/spring/) | **Unverified** | D7 | Generate with useBeanValidation=false and confirm the interface has no @Valid on the body parameter. |
| [Spring MVC validation: @Valid @RequestBody fails with MethodArgumentNotValidException before the method runs](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-validation.html) | Verified | G4, D7 |  |
| [Spring MVC error responses: ResponseEntityExceptionHandler covers HttpMessageNotReadable (400), media type 415/406](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html) | Verified | G3, D6 |  |
| [Spring Boot servlet reference: spring.mvc.problemdetails.enabled is off by default](https://docs.spring.io/spring-boot/reference/web/servlet.html) | Verified | D6 |  |
| [spring-framework #23421: error handler content type vs Accept header can yield 406 with empty body](https://github.com/spring-projects/spring-framework/issues/23421) | **Unverified** | D6 | MockMvc: purchase a missing SKU with Accept: application/json; assert 404, Content-Type text/plain, body "SKU not found". |
| [Jackson 3 migration guide: changed defaults (FAIL_ON_NULL_FOR_PRIMITIVES=true, FAIL_ON_UNKNOWN_PROPERTIES=false, …)](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md) | Verified | G13 |  |
| [Jackson 3 still coerces "10" → 10 and 1.5 → 1 into int by default (no change listed in migration notes)](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md) | **Unverified** | G13 | Using the Boot-configured JsonMapper, readValue {"quantity":"10"} and {"quantity":1.5}; assert whether each fails. |
| [A JSON number above 2147483647 into an int field fails deserialization (→ HttpMessageNotReadableException → 400)](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md) | **Unverified** | G13 | POST {"quantity":3000000000}; assert 400 "Invalid request". |
| [Spring Boot actuator endpoints: only health exposed over HTTP by default; liveness/readiness probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html) | Verified | D10 |  |
| [Jakarta Persistence 3.2 @Entity: class need not be public; needs public/protected no-arg constructor](https://jakarta.ee/specifications/persistence/3.2/apidocs/jakarta.persistence/jakarta/persistence/entity) | Verified | D10 |  |
| [Spring Framework reference: since 6.0, package-visible methods can be @Transactional with class-based proxies](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) | Verified | D10 |  |
| [Package-private Spring Data repository interfaces, @Service and @RestController classes work (common practice, no official statement found)](https://docs.spring.io/spring-data/jpa/reference/repositories/definition.html) | **Unverified** | D10 | Make entity, repository, service and controller package-private; a @SpringBootTest + MockMvc round trip passes. |
| [PostgreSQL docs: collations. Deterministic collations compare byte-for-byte (case-sensitive); "C" sorts by byte value](https://www.postgresql.org/docs/current/collation.html) | Verified | G1, G11 |  |
| [PostgreSQL docs: an index supports one collation per column; queries in another collation can't use it](https://www.postgresql.org/docs/current/indexes-collations.html) | Verified | G11 |  |
| [Java 25 String.compareTo: compares UTF-16 char values (matches "C" order for ASCII)](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/String.html#compareTo(java.lang.String)) | Verified | G11 |  |
| [Shopify Help Center: "SKUs are case-sensitive"](https://help.shopify.com/en/manual/products/details/sku) | Verified | G1 |  |
| [An encoded slash (%2F) in the path segment is rejected by the embedded Tomcat by default](https://tomcat.apache.org/tomcat-11.0-doc/config/http.html) | **Unverified** | G11 | GET /inventory/a%2Fb; assert the status and whether your handler is ever reached. |
| [Google AIP-158: "Adding pagination to an existing RPC is a backwards-incompatible change"](https://google.aip.dev/158) | Verified | G9 |  |
| [Google AIP-180: new client-populated fields must default to the previous behaviour](https://google.aip.dev/180) | Verified | G8, G9 |  |
| [Azure REST guidelines: adding paging later is breaking; list response as object with nextLink](https://github.com/microsoft/api-guidelines/blob/vNext/azure/Guidelines.md) | Verified | G9 |  |
| [Adobe Commerce REST: "If the pageSize is not specified, the system returns all matches"](https://developer.adobe.com/commerce/webapi/rest/use-rest/performing-searches) | Verified | G9 |  |
| [Shopify REST: cursor pagination in the Link header (page_info), default limit 50, max 250](https://shopify.dev/docs/api/usage/pagination-rest) | Verified | G9 |  |
| [GitHub REST: bare-array bodies paginated through the Link header](https://docs.github.com/en/rest/using-the-rest-api/using-pagination-in-the-rest-api) | Verified | G9 |  |
| [RFC 8288 Web Linking (Link header, rel="next")](https://www.rfc-editor.org/rfc/rfc8288) | Verified | G9 |  |
| [Use The Index, Luke: no OFFSET; keyset paging avoids scans and drift](https://use-the-index-luke.com/no-offset) | Verified | G9 |  |
| [PostgreSQL docs: rows skipped by OFFSET are still computed](https://www.postgresql.org/docs/current/queries-limit.html) | Verified | G9 |  |
| [Spring Data JPA: keyset scrolling with Window / ScrollPosition](https://docs.spring.io/spring-data/jpa/reference/repositories/query-methods-details.html) | Verified | G9, D3 |  |
| [oasdiff: adding an optional request parameter is non-breaking](https://www.oasdiff.com/checks/new-optional-request-parameter) | Verified | G8, G9, D7 |  |
| [oasdiff: adding a required request parameter is a breaking change](https://www.oasdiff.com/checks/new-required-request-parameter) | Verified | G8 |  |
| [OpenAPI 3.0.3 Schema Object: "additionalProperties defaults to true"](https://github.com/OAI/OpenAPI-Specification/blob/main/versions/3.0.3.md#schema-object) | Verified | G13 |  |
| [H2 in PostgreSQL mode doesn't run the same DDL/DML (ON CONFLICT DO UPDATE, COLLATE "C", RETURNING)](https://www.h2database.com/html/features.html#compatibility) | **Unverified** | D9 | Run the Flyway migrations and the upsert/purchase queries against H2 in PostgreSQL mode; record which fail. |
| [Spring Framework 7 reference: @Retryable attributes maxRetries, delay, jitter, multiplier, maxDelay, includes; enable with @EnableResilientMethods; total attempts = 1 + maxRetries](https://docs.spring.io/spring-framework/reference/core/resilience.html) | Verified | W2, X1 |  |
| [Spring reference: in proxy mode a self-invocation of a @Transactional method does not start a transaction](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html) | Verified | X1 |  |
| [Spring reference: TransactionTemplate runs a callback in a new transaction with the template's isolation level](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html) | Verified | X1 |  |
| [Order of @Retryable vs @Transactional interceptors when both are on one method (not covered in the resilience chapter)](https://docs.spring.io/spring-framework/reference/core/resilience.html) | **Unverified** | X1 | Force a 40001 on the first attempt and assert the second attempt runs in a new transaction (different txid_current()). |
| [PostgreSQL docs: sum(bigint) returns numeric](https://www.postgresql.org/docs/current/functions-aggregate.html) | Verified | D3, V1 |  |
| [Spring HibernateJpaDialect Javadoc: with prepareConnection true (default), JPA transactions support per-transaction isolation levels](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/orm/jpa/vendor/HibernateJpaDialect.html) | Verified | X1, S1 |  |
