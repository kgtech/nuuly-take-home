# Interview defense

Questions a senior interviewer would ask about the non-obvious choices, each with a short answer grounded in the code and the records, the alternative that was rejected and the price paid. The three weakest points are at the end, with how to answer if asked. Card IDs are on the decision board ([`DECISIONS.md`](../../DECISIONS.md)); `OD-n` are the owner's decisions in [`PROMPT.md`](PROMPT.md); `M-n` are findings in [`critique.md`](critique.md).

## 1. Why path versioning, and why not a header or a dated version like Stripe or GitHub? (H1, OD-2)
- **Answer:** the take-home's four URLs had to stay exactly the spec's, and everything the spec did not ask for (idempotency keys, page-size control, SKU details) had to be additive. A path prefix does that with no default to define for callers that send nothing, and the version is visible in a URL, a log line and the Swagger UI. `/v2` only grows compatibly (additive response fields, new optional inputs); a breaking change gets `/v3`; a version is removed only by an owner decision.
- **Rejected:** a version header (Stripe's `Stripe-Version`, GitHub's `X-GitHub-Api-Version`): invisible in a URL and needs a default. Dated versions: the right shape for a large public API with many small changes, wrong for two versions. Extending the unversioned API in place (build v2): puts behaviour on the spec's paths the spec does not list.
- **Price:** two route sets, two OpenAPI files, and a parity test (`VersionParityIntegrationTest`) to keep both on the same stock. Sources for the provider comparison are in `DESIGN.md` §2.

## 2. Why does an unversioned POST reject `Idempotency-Key` with 400 instead of ignoring it? (H3, OD-4)
- **Answer:** a caller who sends a key believes the write is retry-safe. On the unversioned path it is not, so ignoring the header would let a client double-write while thinking it is protected. Failing loudly is the safe direction; nothing is written or stored (`UnversionedIdempotencyIntegrationTest`, with the `idempotency_keys` table renamed away while the requests run, proves the path never touches it).
- **Rejected:** ignoring it (RFC 9110 §6.3 says recipients SHOULD ignore unrecognized headers: the counter-argument is recorded on H3); honouring it (build v2: the spec never had it).
- **Price:** a client that sends the header everywhere breaks on the spec paths; the README says the unversioned POSTs are not retry-safe and how to reach the keyed twin.

## 3. Why 400, not 409 or 422, for a reused key, and why is an expired key rejected instead of treated as new? (G14, T1)
- **Answer:** one client-error code for every request problem, like the spec. A key reused for another request, an expired key and a malformed key are all "Invalid request". An expired key is rejected because accepting it as new would let a late retry of an old request apply twice; the key window is 24 h by the database clock and rows are kept so the rejection stays true.
- **Rejected:** 422 for a different request and 409 for a concurrent first request (the IETF draft, revision -07, expired and archived, suggests both; Stripe returns an error for changed parameters); the second concurrent request instead waits on the first request's row and replays.
- **Price:** the client cannot tell mismatch from expiry. The earlier reason recorded ("adds status codes the spec does not list") stopped being true when `/v2` added 412; the reason above replaces it.

## 4. Why cap a spec path at 250 rows? A client that ignores `Link` gets a silent truncation. (H4, OD-5, G9, C2)
- **Answer:** an unbounded `GET /inventory` failed with `OutOfMemoryError` at 1M SKUs (C-02), so the unversioned list is a fixed page of 250 with an `after` cursor and a `Link` header. The deviation is written in `openapi.yaml`'s `info.description`, on the operation, and on the first screen of the README. `/v2` adds `limit` (1 to 250).
- **Rejected:** unbounded (memory), keeping `limit` on the spec path (an extension).
- **Price:** a client that ignores `Link` sees 250 of N with no truncation signal other than the `Link` header.

## 5. Why require keys on `/v2`, and what does a client that cannot make UUIDs do? (H2, OD-3)
- **Answer:** an optional key makes retry-safety depend on the caller remembering to send it. On `/v2` a missing or malformed key is 400. Any random UUID v4 is a valid key; the front end makes one per user action and reuses it on retry only for status 0, 408, 429 and 5xx (FE9).
- **Price:** the key lives as long as the browser tab (an in-memory key), so a page refresh between two clicks is a new action.

## 6. Why mirror routes under `/v2` and a `/details` sub-resource? (H6, OD-6)
- **Answer:** the `/v2` routes have the same relative paths as the spec's, so a client moves by changing a prefix. Stock and details are different resources with different concurrency rules: stock changes are keyed writes, details are a full replacement that is idempotent by method and guarded by `ETag` preconditions.
- **Rejected:** build v2's create-with-details POST (201/409): it mixed stock and details, so a retry could double stock or overwrite details.

## 7. Why can `PUT …/details` create a SKU, and what does a typo do? (H7, H8, OD-11)
- **Answer:** creating and describing a SKU in one idempotent request, guarded by `If-None-Match: *` (the Create page always sends it), is the RFC 9110 create-only pattern. `If-Match` on an absent SKU is 412 and creates nothing.
- **Honest cost:** an unconditional PUT to an id nobody created creates a SKU at quantity 0, and there is no delete (G5). In the critique this was reproduced (`ABC-l` appeared in the spec's `GET /inventory`). It is the Target API and OD-11; the front end avoids it, and the README states the cost.
- **The 412 text** ("Details changed since you read them…") is misleading for `If-None-Match`; the text is fixed by the Target API, and the Create page shows its own sentence (M-12).

## 8. Why a decision board, and can someone else reproduce your rules? (OD-9, OD-10)
- **Answer:** the rules in `CLAUDE.md` and `DECISIONS.md` are generated from a board so a decision, its rejected options and its reasoning cannot drift from the rule text. The board's database is dumped in `ai/final/board-db/`; `ai/export-board.mjs` regenerates both files byte for byte (the README has the working command; running it from `ai/` fails because Node resolves `playwright` from the script's directory).
- **Price:** a 450 KB HTML board and a private artifact; cards that described code that was never built (#87's) had to be rewritten to match the code (E1, E2, E3, A37 to A39).

## 9. You replaced SERIALIZABLE ledger sums with a balance row. Where is that story? (D4, V1, W1, E1; `DESIGN.md` §4)
- **Answer:** the first build derived every balance from a `SUM` over an append-only ledger at SERIALIZABLE with retries; concurrent purchases of different SKUs then hit serialization failures (#26). Build v2 keeps a balance per SKU as a row updated by a conditional `UPDATE … WHERE quantity >= :q` at READ COMMITTED (the second writer re-evaluates the predicate on the committed row: PostgreSQL docs, Read Committed) and still appends every change to the ledger. `DESIGN.md` §4 opens with this history; the older cards carry "superseded by E1".
- **Price:** two records of one fact (balance and ledger). The invariant "balance equals ledger SUM" is asserted after every integration test (`BalancesRecordedExtension`).

## 10. Why is the ledger append-only by trigger, and is it really? (G5, A11, E3)
- **Answer:** a trigger raises `P0001` on `UPDATE` and `DELETE`, so a bug in the API cannot rewrite history. It protects against the API, not against the database owner: the app connects as the owner (the critique confirmed `TRUNCATE` and `DISABLE TRIGGER` work). Privilege separation was weighed and left out of a take-home.

## 11. Why does the guard filter run before `@Valid`, and why "first listed" for equal Accept ranges? (H11, H16 = M-13)
- **Answer:** Spring strips `;matrix` content before binding, so `/inventory/ABC-1;lot=7` would reach SKU `ABC-1`; a filter on the routed path answers it as the malformed id it is, before any binding. The price is that a `;` skuId with a bad body on purchase answers 404 instead of the body's 400. For equally specific Accept ranges the first listed decides (build v2's behaviour, kept; C3's "highest q" text was changed to match).
- **Route coverage:** the filter is a route table keyed by route kind, and `RouteGuardCoverageTest` fails for any mapped `{skuId}` route the table does not know (lesson L21: build v2's filter skipped `/v2` purchase).

## 12. Why does the request hash include the API version, and why a `NOT VALID` migration? (H9, H10)
- **Answer:** a key stored by an unversioned request holds an `InventoryItem` body; without the version in the hash it would replay that body on `/v2`. V4 narrows the idempotency CHECKs with `NOT VALID` so a database that already holds build v2's `create`/201/409 rows cannot fail the migration. Both are defensive: no build-v2 database is claimed to exist.

## 13. Why is the `/v2` ETag only the details version? (Target API, A27, M-05)
- **Answer:** the contract says so. It is harmless while `Cache-Control: no-store` holds and conditional GET headers are ignored, and it is a known deviation from RFC 9110 §8.8.1 and §9.3.4 (a strong validator should change with the representation). Honouring `If-None-Match` on GET would need `/v3` or a separate details resource.

## 14. Why is CI not running? (deviation D-1)
- **Answer:** the GitHub token has no `workflow` scope, so the workflow could not be pushed. It is parked at `ai/final/ci-workflow.yml`, `scripts/gate.sh --e2e` runs the same steps locally, and every PR was merged only after it passed on the branch. The last run on the final tip is recorded in the README. I did not use another credential to get around the scope.

## 15. Why so many tests (about 960 Java tests for 2.7k lines of main code)? (lessons, H15)
- **Answer:** every SQL statement and constraint runs against real Postgres; the ArchUnit rules keep the unversioned path free of idempotency code (L31), keep raw path reads in one class (L19) and keep tests on one container (L27). The critique ran 57 mutations against the Java suite: 52 were killed by behavioural tests, one real survivor was found and fixed (M-04). Duplicate matrices across layers are the known cost (M-26).

## 16. The Create page is two steps. What if the browser dies between them? (FE33, FE34)
- **Answer:** the SKU then exists at stock 0 with its details; the initial quantity is lost and the user adds stock from the SKU page (recorded in FE34). If the create response is lost and the user clicks again, the 412 says the earlier attempt most likely created it and sends the user to the SKU page instead of silently dropping the stock.

## Three weakest points
1. **The strong ETag that does not change with stock (M-05).** It is the contract, not an accident, but a reviewer who knows RFC 9110 will notice. Answer: it is the details validator under `no-store` with conditional GET ignored, recorded with the RFC sentences; the fix is a future `/v3` or a separate details resource, and I would not honour `If-None-Match` on GET before that.
2. **A typo'd unconditional `PUT` creates a phantom SKU that can never be deleted (M-06).** Answer: it is what the Target API and OD-11 prescribe; the UI cannot do it, the README states the cost, and the safe alternative (428 for a create without a precondition) would be a small change if the owner wants it.
3. **CI has never run on GitHub, and the app is the database owner (D-1, M-36).** Answer: the token lacked the `workflow` scope and I did not work around it; the local gate is the record. The ownership point is deliberate scope: the append-only trigger protects against the API, not against whoever holds the database credentials.
