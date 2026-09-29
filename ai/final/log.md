# Final build log

Times are UTC. Tags: [setup] [study] [gate] [board] [service] [frontend] [critique].

- 2026-09-29T18:54:22Z [setup] Step 1: fetched origin; no `final` branch locally or on the remote.
- 2026-09-29T18:54:22Z [setup] Step 2: created `final` from origin/v2 (14c3d05 on top of 9c1d5cc); copied ai/final/PROMPT.md and ai/final/lessons.md from origin/main as is; pushed.
- 2026-09-29T18:54:22Z [setup] Step 3: moved v2 records with git mv: PROMPT.md, lessons.md, issues.md, DESIGN-V2.md, DEVIATIONS.md, DECISIONS-ADDED.md -> ai/v2/ (same names); FABLE_REPORT.md -> ai/v2/REPORT.md; .fable/ -> ai/v2/run-records/; DECISIONS.md -> ai/v2/DECISIONS-v2.md; CLAUDE.md -> ai/v2/CLAUDE-v2.md. Removed spec/ (verified byte-identical to docs/ copy).
- 2026-09-29T18:54:22Z [setup] Copied as is from origin/main: ai/* except ai/final/ (Prompt Template.md, codebase-critique.md, decision-queues/, decision-review.md, export-board.mjs, github-issues.md, research-sources.md), agent-prompts.md, docs/NUULY-ASSESSMENT-README-JUL-2026.md.
- 2026-09-29T18:54:22Z [setup] Copied as is from origin/feat/issue-87-align-with-v2: ai/decision-board.html, DECISIONS.md, CLAUDE.md (#87's exports; the code doesn't follow them fully yet).
- 2026-09-29T18:54:22Z [setup] Changed README.md links to the moved files (DESIGN-V2, DECISIONS-ADDED, DEVIATIONS, PROMPT, issues, lessons, REPORT, run-records, docs/). Code comments that mention DESIGN-V2 sections left as is (docs-only commit).
