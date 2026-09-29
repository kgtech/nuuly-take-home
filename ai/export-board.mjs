// Runs the decision board's own genDecisions/genClaude headless and writes DECISIONS.md and CLAUDE.md.
//
// Usage: node ai/export-board.mjs <board.html> <db-dir> <out-dir> <yyyy-mm-dd>
//   board.html  ai/decision-board.html
//   db-dir      one JSON file per board decision (<id>.json with choice, notes, why), dumped from the
//               board artifact's `decisions` collection (ArtifactData list with out_dir)
//   out-dir     where DECISIONS.md and CLAUDE.md are written
//   yyyy-mm-dd  date stamped in the DECISIONS.md header; use the committed file's date to check that the
//               unchanged board reproduces it byte for byte, and today's date to regenerate
// Env: PW_EXE optionally points at an installed Chromium when the playwright package and the
// installed browsers are different versions.
import { chromium } from 'playwright';
import fs from 'fs';
import path from 'path';

const [, , board, dbdir, outdir, date] = process.argv;
if (!board || !dbdir || !outdir || !/^\d{4}-\d{2}-\d{2}$/.test(date || '')) {
  console.error('usage: node ai/export-board.mjs <board.html> <db-dir> <out-dir> <yyyy-mm-dd>');
  process.exit(2);
}

let html = fs.readFileSync(board, 'utf8');
const marker = 'let lastExport="";';
if (!html.includes(marker)) throw new Error('export marker not found in ' + board);
html = html.replace(marker, 'window.__gen={genDecisions,genClaude,computeConflicts};' + marker);
fs.mkdirSync(outdir, { recursive: true });
const page_ = path.resolve(outdir, '_board.html');
fs.writeFileSync(page_, html);

const state = {};
for (const f of fs.readdirSync(dbdir).filter(f => f.endsWith('.json'))) {
  const d = JSON.parse(fs.readFileSync(path.join(dbdir, f), 'utf8'));
  const v = d.data ?? d;
  state[f.slice(0, -5)] = { choice: v.choice ?? null, notes: v.notes || '', why: v.why && typeof v.why === 'object' ? v.why : {} };
}

const browser = await chromium.launch(process.env.PW_EXE ? { executablePath: process.env.PW_EXE } : {});
try {
  const page = await browser.newPage();
  await page.addInitScript(({ state, date }) => {
    localStorage.setItem('sku-decisions-v1', JSON.stringify(state));
    const [y, m, d] = date.split('-').map(Number);
    const Real = Date;
    globalThis.Date = class extends Real {
      constructor(...a) { if (a.length) super(...a); else super(y, m - 1, d, 12); }
      static now() { return new Real(y, m - 1, d, 12).getTime(); }
    };
  }, { state, date });
  await page.goto('file://' + page_);
  await page.waitForFunction(() => window.__gen);
  const out = await page.evaluate(() => ({ dec: __gen.genDecisions(__gen.computeConflicts()), cl: __gen.genClaude() }));
  fs.writeFileSync(path.join(outdir, 'DECISIONS.md'), out.dec);
  fs.writeFileSync(path.join(outdir, 'CLAUDE.md'), out.cl);
} finally {
  await browser.close();
  fs.rmSync(page_, { force: true });
}
