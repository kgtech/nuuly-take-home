# Board report (final-board)

Inputs: ai/decision-board.html (orig copy: board-scratch/gen/board.orig.html), main's dump /private/tmp/claude-501/bd (pristine) and bd-work (identical).
Baseline check: orig board + bd reproduced committed DECISIONS.md / CLAUDE.md byte for byte (2026-09-29).
Generator (reproducible): board-scratch/gen/{build.mjs,docs.mjs,spec_new.mjs,spec_changes.mjs,spec_refines.mjs}; run `node build.mjs <board.html>` then `node docs.mjs`.

## Mechanism
- One block "ROUND 14" inserted before the final PAIR_RULES.push: DECISIONS.push(new cards) and, for each changed card, options.push({k:next letter, label:"final: ...", rules, assumption, baked:true}), rec moved to the new option, `by` gained the H links, and the old option's REFINES entries were copied to REFINES[card:newLetter] (entries aimed at other changed cards dropped; three text edits, see spec_refines.mjs).
- `baked:true`: the new option's rules are the complete final text (the old option's refinements are already inside it), so refinesFor() skips refinements aimed at a baked chosen option. One-line change in refinesFor. Consequence: changed cards show no "Refined by" line and no "(refined by ...)" suffix; cross references are inline (H2, H3, ...).
- Nav group "Round 14: the final build", tag "Round 14 · final build", subtitle sentence added.
- Old chosen option stays as a rejected alternative with a reason ("Superseded by ...") stored in the doc's why map.

## Cards added (25): H1..H16 (H16 = board-cards' H-M13, text mentions M-13), A17 A19 A22 A25 A26 A27 A30 A35 A36
All chosen option A; notes cite OD-n or "Approved at the plan gate (2026-09-29)". Rule text is board-cards.md's, verbatim (prefix [Hn] is added by the generator). Kinds: H1-H8, H10, H11, H16, A17, A19, A25, A26 = gap (API contract); H9, H12-H15, A22, A27, A30, A35, A36 = design.

## Cards changed (50), each now chooses the new letter (label starts "final: ")
Old -> new choice: G8 A->E, S3 B->D, U3 A->C, A34 A->D, A33 A->D, G9 B->E, R4 A->D, R8 B->D, C2 A->E, Z3 B->D, G10 D->E, G6 A->D, G3 A->C, R3 A->D, G11 A->D, G13 A->D, Y1 A->C, U2 C->D, G14 A->D, R1 B->D, R2 A->E, S8 B->D, T1 D->E, R9 B->E, Y3 A->C, Y4 A->C, A18 B->C, C3 A->D, S2 C->D, S12 A->E, D7 A->D, S11 A->D, D5 A->E, E1 A->E, E2 A->D, E3 A->E, S1 B->E, S4 B->E, A37 A->D, A38 B->D, A39 A->D, Z2 B->C, D10 A->D, C1 A->E, S10 A->D, T6 A->E, G5 A->D, A14 A->D, U1 A->C, D9 A->D
Beyond the required list I also changed: S2 (its text said controllers pass the raw segment), U1 (stored on /v2 only), D9 (mixed-version concurrent purchase test). (All 50 render as "final:" in DECISIONS.md.)
A14 was "only if needed": needed, its text said the invariant helper runs after the V3 backfill, which does not exist.
Indirect changes (unchanged cards whose effective text moved because a refiner changed): S5 (drops web.TextErrors / web.TextErrorReportValve, keeps A38's OutcomeResponses text), S6 (InventoryErrorAdvice, not web.InventoryErrorAdvice), D3 (adds DetailsRepository), Y2 test line (PackageBoundaryTest does not scan for retries; only ArchUnit does). All other unchanged cards are byte-identical to before.

## Conflicts
computeConflicts on the final set: only V1 ("About 4h more, plus a new concurrency design", main's own SINGLE_RULE on V1-C, present in the committed baseline). No new conflict, no "not in scope" notes. Left as is: V1 is main's decision, not part of final's changes.

## Ambiguities and choices
- H7's "Rec" (If-None-Match other than * → 400) is not in the rule text of board-cards.md; I added it as a second H7 rule because the gate approved every recommendation. It says the first test decides what a malformed If-Match does.
- H15 has no "Rule:" text in board-cards.md; I wrote one from its prose (five rules + relaxation).
- A17/A19/A22/A25/A26/A27/A30/A35/A36: board-cards gives short rule text; I expanded each to a full sentence and invented plausible rejected options (with reasons). A19 text states the 4 KB/64 KB caps of H11.
- A38: kept the typed WriteResult<O> / one Page<T> design, because board-cards recommends (and the gate approved) the removal-PR refactor; the card describes that end state, adapted (unversioned entry points never return Stored, PUT details has its own result, unversioned Link omits limit). Same for Paging (PR 7), OpenApiConfiguration in inventory.web (PR 11) and ArchUnit (PR 3): named in the rules but they do not exist in the tree today.
- A37: "CARD" option chosen (advice/valve/container settings stay in inventory.web; web/ holds only HttpConstants); OpenApiConfiguration placed in inventory.web (not stated in the sources).
- E1: purchase is UPDATE + second SELECT EXISTS as in StockRepository (verified by reading). The "a create that commits between the two statements turns NotFound into Insufficient, which DESIGN-V2 accepts" sentence comes from current-state.md (DESIGN-V2 not re-read).
- T6 is recorded as "Superseded by H14" (same pattern as the board's other superseded cards); H14 carries the rule.
- rec.k of every changed/new card points at the chosen option (the gate approved every recommendation), so "Matched recommendation" is Yes throughout.
- Header of DECISIONS.md has no card count; the table has 104 rows (79 + 25).

## Not verified
- Claims marked U in board-cards.md (Stripe/GitHub conventions, Spring PUT conditional headers, NOT VALID on Postgres 18, springdoc groups) are carried as decisions, not proven; they are the first tests of Part 2.
- "main's V1 differs, so Flyway's checksum check refuses a main database" is from current-state.md (git show origin/main was not re-run).
- ArchUnit "built with 1.5.1" kept from the old A39 text; the version is not in gradle/libs.versions.toml yet.
- IfMatch behaviour for malformed values, Cache-Control no-store on GET (A27), unversioned chunked body uncapped (A19): unproven.
- The published artifact was not touched; I did not write to any database.

## Repo files changed
ai/decision-board.html, ai/Prompt Template.md, DECISIONS.md, CLAUDE.md (regenerated by ai/export-board.mjs with bd-out and date 2026-09-29; rerun gives identical bytes).
