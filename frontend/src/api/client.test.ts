import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { createClient } from './client';
import { server, store, TEXT } from '../test/server';

const api = createClient('http://localhost:3000');

describe('client', () => {
  it('lists with limit and after and exposes the next link', async () => {
    store.seed({ A: 1, B: 2, C: 3 });
    const r = await api.listInventory({ limit: 2 });
    expect(r.ok).toBe(true);
    if (!r.ok) throw new Error();
    expect(r.data).toEqual([
      { skuId: 'A', quantity: 1 },
      { skuId: 'B', quantity: 2 },
    ]);
    expect(r.next).toContain('after=B');
    const r2 = await api.listInventory({ limit: 2, after: 'B' });
    if (!r2.ok) throw new Error();
    expect(r2.data).toEqual([{ skuId: 'C', quantity: 3 }]);
    expect(r2.next).toBeNull();
  });

  it('follows a Link URL through its own origin', async () => {
    store.seed({ A: 1, B: 2, C: 3 });
    const r = await api.listInventoryAt('http://other-host:8080/inventory?limit=1&after=A');
    if (!r.ok) throw new Error();
    expect(r.data).toEqual([{ skuId: 'B', quantity: 2 }]);
  });

  it('returns the text/plain body verbatim on errors', async () => {
    const r = await api.getInventory('missing');
    expect(r).toEqual({ ok: false, status: 404, errorText: TEXT.notFound });
  });

  it('sends the Idempotency-Key header', async () => {
    const key = '123e4567-e89b-42d3-a456-426614174000';
    const r = await api.addStock('A', { quantity: 3 }, key);
    expect(r.ok).toBe(true);
    expect(store.requests[0]?.headers.get('Idempotency-Key')).toBe(key);
    const again = await api.addStock('A', { quantity: 3 }, key);
    if (!again.ok) throw new Error();
    expect(again.data).toEqual({ skuId: 'A', quantity: 3 });
  });

  it('reports a network failure with status 0', async () => {
    server.use(http.post('*/inventory/:skuId/purchase', () => HttpResponse.error()));
    const r = await api.purchase('A', { quantity: 1 }, null);
    expect(r.ok).toBe(false);
    if (r.ok) throw new Error();
    expect(r.status).toBe(0);
    expect(r.errorText).toMatch(/network/i);
  });
});
