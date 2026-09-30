import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, expect, it } from 'vitest';
import config from '../vite.config';

// After OD-7 the front end calls only /v2, so the dev server must not proxy the unversioned API.
describe('vite dev proxy (OD-7)', () => {
  it('proxies /v2 and nothing unversioned', () => {
    const proxy = Object.keys((config as { server?: { proxy?: Record<string, unknown> } }).server?.proxy ?? {});
    expect(proxy).toEqual(['/v2']);
  });

  it('has no /inventory entry in the file text', () => {
    const text = readFileSync(resolve(process.cwd(), 'vite.config.ts'), 'utf8');
    expect(text).not.toMatch(/['"`]\/inventory/);
  });
});
