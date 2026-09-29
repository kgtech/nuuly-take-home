# Board follow-ups (MINOR findings from the PR #107 review, batched for one touch-up)

Each needs a board edit and a regenerate (never hand-edit DECISIONS.md or CLAUDE.md, OD-9). Cards found wrong or stale during the build are added here and fixed in one board PR before the report.

1. G3 says every client error on PUT details is 400, which contradicts H7/H8 (412 on a failed precondition). Reword: 400 for every client error except a failed precondition (412).
2. H7's second rule bullet contains "the first test decides"; move that sentence out of the rule text into the card's assumption.
3. A19/H11: state what a chunked /v2 POST body gets (counted while read, same cap as Content-Length) and that unversioned chunked bodies are uncapped.
4. A17, A19, A22, A25, A26, A27, A30, A35, A36 carry rejected options written for them (board-report.md admits it); mark those reasons as reconstructed from build v2's DECISIONS-ADDED where they were not the owner's.
5. C1 ("create -> 400") and R7 ("POST create") still say "create" after OD-6: say "add".
6. issues.md F-10 makes typed WriteResult<O> / one Page<T> conditional while A38 states it unconditionally: settle when the removal PR lands.
7. Add a note for OD-7 in frontend/DECISIONS.md (front end calls only /v2).
8. E1 cites DESIGN-V2 without its path (ai/v2/DESIGN-V2.md). board-cards.md calls H16 "H-M13".
9. (PR #108 review) A19/H11 state 4 KB for both POSTs on both versions; until the combined /v2 add/purchase PR lands, the /v2 item POST (build v2's create) is 64 KB. The PR removes the temporary state; check the wording then.
10. (PR #108 review) ArchitectureTest L19 could also forbid getPathInfo/getPathTranslated; RouteGuardCoverageTest only enumerates patterns containing `{skuId}` (a route with another variable name is not enumerated) and checks only the ';' answer; RoutedPathTest could pin double slashes, trailing slash and upper-case literals. MINOR; do if budget allows.
11. (PR #111 review) A38 says typed WriteResult<O> / one Page<T>; the removal PR kept the sealed non-generic WriteResult (the impossible Stored/InvalidRequest arm in OutcomeResponses.toStored remains). Change A38 to match the code, or do the refactor if budget allows; also A17/H2 wording fine.
12. (PR #111 review) README.md and DESIGN.md still describe /v2 create (201/409), PUT with If-Match, optional key and 'pending the owner' hash/V4: covered by F-14 (docs).
