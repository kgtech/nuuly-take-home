# Area 6: security and operations (SHA 436cacae). Stack on APP_PORT=18206, compose project crit6; torn down.

### F-6-01 [MINOR] Liveness probe starves when Postgres is down under load (30 s Hikari wait, 200 Tomcat threads)
- Location: /actuator/health/liveness; application.yaml (no spring.datasource.hikari.connection-timeout, no server.tomcat.threads/accept limits)
- What is wrong: With Postgres stopped, every DB request blocks 30 s waiting for a connection, then answers 500. 260 concurrent requests occupy all 200 Tomcat threads, so the liveness endpoint (which touches no DB) cannot be served. An orchestrator would kill a healthy JVM; the outage becomes a restart loop. Readiness and /actuator/health also take 30 s to say DOWN, longer than the compose healthcheck timeout (5 s) and typical k8s probe timeouts.
- Evidence: `docker stop crit6-postgres-1`; `/actuator/health/readiness` -> `503 30.02s`; `/inventory` -> `500 30.13s`; then 260 parallel `curl /inventory` and `curl -m 8 /actuator/health/liveness` -> `000 8.0s` (timeout). After restart of Postgres liveness returned 200 again.
- Suggested fix: set hikari connection-timeout to about 2-3 s (fail fast), and optionally serve probes on a separate management port.

### F-6-02 [MINOR] Unversioned POST bodies are uncapped when chunked (A19, confirmed): about 20x heap amplification
- Location: InventoryRequestGuardFilter.java (last branch: "Frozen, not changed (A19)"); CappedBodyRequest is only applied on /v2
- What is wrong: A chunked POST /inventory/{id} has no cap. Whitespace is streamed harmlessly (300 MB accepted as 200 in 0.6 s, RSS flat at 377 MB). But an ignored unknown property holding a string just under Jackson's 20 MB string limit is buffered: 60 concurrent requests of 19 MB (1.1 GB on the wire) drove container RSS from 377 MB to 4.3 GB (heap is MaxRAMPercentage=75 of the host, since compose sets no memory limit). Under a real container limit (say 512 MB) this is an OOM kill. Not an auth issue: any client can do it. Impact is bounded by Jackson's default limits and the JVM heap, so no data loss.
- Evidence: 6 requests: `200 x4` and RSS 740 MiB; 60 requests: all 200, `docker stats` 4.326GiB / 7.817GiB, `VmRSS 4501260 kB`, 99 threads. 300 MB whitespace body: `{"skuId":"BIG1","quantity":1} 200`, v2 same body: `400`.
- Suggested fix: wrap unversioned writes in CappedBodyRequest too (the cap is already 4096); the "frozen" reason is only a wish to leave A19 untouched, and the wrapper is generic.

### F-6-03 [MINOR] No memory limit or pids limit on the app in compose; JVM heap follows the host
- Location: compose.override.yaml, Dockerfile (`-XX:MaxRAMPercentage=75.0`)
- What is wrong: with no `mem_limit`/`deploy.resources`, 75% means 75% of the host (5.9 GB on the 7.8 GB test host), so F-6-02 is not stopped by the JVM, and a reviewer's laptop can be squeezed. `HostConfig.Memory` is 0 in `docker inspect`.
- Evidence: `docker inspect crit6-app-1 --format '{{.HostConfig.Memory}}'` -> `0`.
- Suggested fix: add `mem_limit: 768m` (or similar) to the app service so the percentage means something.

### F-6-04 [NIT] Base images are floating tags, database credentials are the default trivial ones, app published on all interfaces
- Location: Dockerfile (`eclipse-temurin:25-jdk`, `:25-jre`), compose.yaml (`postgres:18`, POSTGRES_PASSWORD: inventory), compose.override.yaml (`"${APP_PORT:-8080}:8080"`)
- What is wrong: tags are not digest-pinned (builds are not reproducible, no supply-chain pin). The password is `inventory` in the compose file, but Postgres is published on `127.0.0.1` only (good) and the app port is `0.0.0.0` (no auth by G10, so any LAN host can write stock). Acceptable for a take-home; README could say bind to loopback for a demo.
- Evidence: files as quoted; `docker port` not needed, mapping is in the file.
- Suggested fix: `"127.0.0.1:${APP_PORT:-8080}:8080"`; pin digests if reproducibility is claimed.

### F-6-05 [NIT] Default library endpoints are enabled and log a production warning
- Location: springdoc (`/v3/api-docs`, `/swagger-ui.html` -> 200/302); startup log
- What is wrong: springdoc logs "SpringDoc /v3/api-docs endpoint is enabled by default. To disable it in production, set springdoc.api-docs.enabled=false". The docs are intended (reviewers use Swagger), so this is only an operational note: nothing in the README says how to switch them off for production.
- Evidence: `curl -o /dev/null -w '%{http_code}' /v3/api-docs /swagger-ui.html` -> `200`, `302`; WARN lines at startup.
- Suggested fix: one README sentence on the two properties.

### F-6-06 [NIT] No X-Content-Type-Options, Cache-Control or other security headers on responses
- Location: every response (e.g. `GET /inventory/NOPE`)
- What is wrong: `Content-Type: text/plain` errors carry no `X-Content-Type-Options: nosniff`, and no Cache-Control. Bodies are fixed strings and never reflect input, so exploitability is nil; JSON reads may be cached heuristically by intermediaries (no Last-Modified, so unlikely).
- Evidence: `curl -si localhost:18206/inventory/NOPE` shows only Content-Type, Content-Length, Date.
- Suggested fix: optional filter adding nosniff and `Cache-Control: no-store`; or state it as out of scope.

### F-6-07 [NIT] A client-supplied malformed header logs a stack trace at INFO
- Location: Tomcat Http11Processor (log only)
- What is wrong: a request with a bare CR in a header value (`Idempotency-Key: x\r\nFAKE`) logs "Error parsing HTTP request header" with a full stack trace at INFO for the first occurrence (later ones at DEBUG). Response is 400. Not log injection (the newline is rejected, the value is never logged), just noise.
- Evidence: `docker logs crit6-app-1`: `The HTTP header line [FAKE0x0d] does not conform to RFC 9112. The request has been rejected.` followed by `at org.apache.coyote...`.
- Suggested fix: none needed; optionally set `logging.level.org.apache.coyote.http11.Http11Processor=WARN`.

### F-6-08 [MINOR] No CI actually runs: gate.sh points at a workflow file that is not under .github/
- Location: scripts/gate.sh header ("move that file to .github/workflows/ci.yml once the GitHub token has the workflow scope"); `.github` absent at the SHA (`ls -a .github` -> no such directory)
- What is wrong: the gate is manual only. The README/DESIGN may claim a gate; a reviewer cloning the repo sees no CI. Process gap, not a code bug. (I read only scripts/gate.sh; ai/final/ci-workflow.yml was not opened.)
- Evidence: `ls -a .github` in the checkout: `No such file or directory`.
- Suggested fix: state in README that CI is deferred, or commit the workflow when the token allows.

## Checked and fine
- SQL injection: every native statement in StockRepository, DetailsRepository, IdempotencyStore uses named parameters; no string concatenation of request data (only `.formatted(IMAGES)`, a constant). Payloads in `after` (`' OR 1=1--`), `limit` (`1;drop table sku`), and skuId (`A' OR '1'='1`) returned `[]`, 200 (limit ignored) and 404. A limit of 21 digits is ignored (200).
- Path handling: `//inventory/A1`, `/./`, `/../`, `/INVENTORY`, `/inventory/a1`, trailing slash, `%2e`, `%5c`, `%00` give 404/400, never a hit on another SKU. Decomposed `%C3%85BC` is rejected by the skuId pattern (400), so no Unicode normalization ambiguity; case-sensitivity intact.
- Request smuggling-ish: duplicate Content-Length -> 400, CL+TE -> 400, 200 extra headers -> 400 text/plain, 9000-byte query -> 400, 7000-byte query -> 200, HTTP/1.0 -> 200 with close, pipelined requests answered in order. All error bodies text/plain.
- TRACE returns 405 "Method Not Allowed", no echo. X-HTTP-Method-Override is ignored (POST stays POST). No CORS headers on a cross-origin OPTIONS (no allow-origin), OPTIONS answers with Allow list only.
- Actuator: only `health` exposed (`/actuator/env` 404); health shows component names without details. `/actuator/health/liveness` fine while DB is up. Stack traces never appear in response bodies (500 body is "Internal server error").
- Log injection: header newlines rejected by Tomcat; `%0A` in a skuId path gives 404 and nothing logged.
- Slow body (partial JSON, 25 s idle) left to Tomcat's default connection timeout (20 s); no crash. Graceful shutdown works ("Commencing graceful shutdown ... complete" in logs, SIGTERM clean).
- Query plans with 100k SKUs: keyset page uses `Index Scan using sku_pkey` (7 buffers, 0.08 ms), the v2 page join an Index Only Scan (0.05 ms); no seq scans, no amplification through `after` (even a non-ASCII `after` returned in 40 ms).
- Idempotency growth measured: 3003 rows = 640 kB total including PK index, about 218 bytes/row, so about 218 MB per million keyed requests including 404s. The README's operational note (estimate "a few hundred bytes", clearing statement) is adequate; the clearing UPDATE satisfies the all-NULL CHECK, and a cleared key replayed returned 400 as described. Note the README statement that keys are per keyed /v2 request incl. 404s is accurate (3000 unauthenticated 404s each stored a row): unauthenticated table growth is a known consequence of G10 + R9.
- Container: runs as uid 999 `app` (non-root), healthcheck present and reports healthy, Postgres bound to 127.0.0.1, layered jar. Readiness includes db, liveness does not (correct semantics, but see F-6-01 for the starvation).
- Dependencies: Spring Boot 4.1.1, Tomcat 11.0.24, Spring 7.0.9, tools.jackson 3.1.5 (jackson-databind 2.22.1 also present transitively), PostgreSQL driver 42.7.13, Flyway 12.4.0, springdoc 3.1.1, swagger-ui 5.32.14, snakeyaml 2.6. `npm audit --omit=dev` in frontend: `found 0 vulnerabilities`. I did not look up CVE databases for the Java versions (UNVERIFIED; no network CVE check was run beyond noting the versions).
