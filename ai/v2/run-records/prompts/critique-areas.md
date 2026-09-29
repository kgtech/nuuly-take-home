# Area instructions (one reviewer each)

## spec — Spec conformance
Every acceptance criterion in `issues.md` (service stories #1–#8, #15 and the review requirements #21–#30, as restated for V2 in the `[v2]` issues #43–#61) and every spec requirement in `spec/` and `openapi.yaml`: status codes, response shapes, text/plain errors and their exact texts, the four operations, paging, the Idempotency-Key header. Map each criterion to the code and test that meet it; a criterion with no code or no test is a finding.

## conc — Concurrency and data integrity
The conditional UPDATEs (`StockRepository`), the ledger insert in the same transaction, overflow and cap handling, the trigger, anything that can lose or corrupt stock under concurrent requests, including two app instances, a crash between statements, and READ COMMITTED semantics of `INSERT ... ON CONFLICT DO NOTHING` under concurrent claims. Check the concurrency tests can fail (thread counts, assertions on totals, bounded waits).

## storage — Storage design
Every invariant in `DESIGN-V2.md` §3 / PROMPT "V2 storage design" (no oversell, exactly once incl. after Redis expires/evicts/loses the key, durable, recorded, bounded staleness, defined failure): does the code do what `DESIGN-V2.md` says; does the failure behaviour match §4; are the Lua script semantics (versions, onlyIfPresent, TTL) right; can a Redis hit ever contradict Postgres beyond the stated bound; the after-commit hooks; any performance claim without a benchmark. Compare `.fable/current-implementation.md` with `main` (`git show main:<path>` in the checkout works) for accuracy.

## api — API contract and error handling
Code vs `openapi.yaml` (springdoc annotations, the committed export), validation behaviour as decided (G3, G4, G11, G13, S2, S3, U2, U3, Y1, Z3, C1, C2), framework errors never leaking JSON or stack traces, `InventoryRequestGuardFilter`, `InventoryErrorAdvice`, the Tomcat valve, TRACE, library paths, the Link header.

## tests — Tests
Do the tests exercise the real SQL and Redis; do they cover the edge cases; can any pass for the wrong reason (mocked what it should hit, asserting only status, weak awaits); tests that could be merged (same behaviour at two layers, matrices repeated) or that cannot fail; test classes that start their own container or Spring context without needing to (count the distinct context configurations); the cleanup/seed helpers vs the triggers; the fixed Redis host port.

## fe — Front end
`frontend/`: contract fidelity through the generated types (no hand-written API types), the required views and their loading/empty/error states, Link paging, the Idempotency-Key lifecycle (once per action, reused on retry, dropped when inputs change or on a definitive response), errors shown verbatim, accessibility (labels, roles, focus, keyboard), phone width, client validation never blocking a request the server would accept, the Vite proxy, no service changes; Vitest/MSW and Playwright coverage of the required flows.

## sec — Security and operations
Input limits (skuId length, quantity range, body size, header size), injection (SQL parameters, Lua script arguments, log injection), configuration and secrets (compose credentials, ports on loopback), logging (no request bodies or keys at INFO/WARN), the Docker image (user, heap policy, healthcheck), what an operator needs (README run instructions, Redis and Postgres sizing, retention of idempotency rows, failure behaviour), actuator exposure.

## intv — Interview defense
Read the build as a senior interviewer reviewing a take-home. For every non-obvious choice (the V2 storage design and why it beats the approach on `main`, what Redis is trusted with, the idempotency flow and the Redis fast path, how hot SKUs are chosen, the TTL bound, pagination, strict validation, READ COMMITTED without retries, the append-only trigger, the front-end stack and its idempotency-key lifecycle), write the question the interviewer would ask and check whether the code, `DECISIONS.md`, `DESIGN-V2.md`, `DECISIONS-ADDED.md`, `frontend/DECISIONS.md` and the README answer it: the reason, the alternative rejected, and the tradeoff accepted. A finding is a choice with no defensible answer on record, an answer the code contradicts, or complexity the take-home's scope doesn't justify.

## std — Industry standards
Compare the design with how production commerce and payment APIs handle the same problems, and cite a source (URL) for every claim. Cover at least: Shopify's Admin API inventory model (inventory items, levels, locations; adjust vs set; overselling; retries/idempotency for mutations); Stripe's Idempotency-Key behaviour and the IETF HTTP API working group's Idempotency-Key header draft (key scope, lifetime, response to a reused key with a different request, response while the first request is in progress); at least one other inventory API (Square, BigCommerce or commercetools) for stock adjustments and concurrency; pagination (Link header, cursor) and error-format conventions (RFC 9457 problem details vs text/plain) for public HTTP APIs. For each difference, say whether it matters at this service's scope. A difference is a finding only if a user or operator would be affected, or if it contradicts a standard the service claims to follow. Use web search / WebFetch on the providers' current documentation. If web access isn't available, say so and mark each claim UNVERIFIED instead of relying on memory. Don't repeat claims from `lessons.md` or the first build's design log without checking them.
