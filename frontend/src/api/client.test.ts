import { describe, expect, it } from 'vitest';
import { http, HttpResponse } from 'msw';
import { createClient, type SkuDetails } from './client';
import { server, store, TEXT } from '../test/server';

const api = createClient('http://localhost:3000');
const details: SkuDetails = { name: 'Linen dress', description: 'Midi', cost: { amount: 12900, currency: 'USD' }, images: [] };

describe('client v2 reads', () => {
  it('lists with limit and after and exposes the next link', async () => {
    store.seed({ A: 1, B: 2, C: 3 });
    store.seedDetails('B', 2, details);
    const r = await api.listSkus({ limit: 2 });
    expect(r.ok).toBe(true);
    if (!r.ok) throw new Error();
    expect(r.data).toEqual([
      { skuId: 'A', quantity: 1 },
      { skuId: 'B', quantity: 2, details },
    ]);
    expect(r.next).toContain('/v2/inventory?');
    expect(r.next).toContain('after=B');
    const r2 = await api.listSkus({ limit: 2, after: 'B' });
    if (!r2.ok) throw new Error();
    expect(r2.data).toEqual([{ skuId: 'C', quantity: 3 }]);
    expect(r2.next).toBeNull();
  });

  it('follows a Link URL through its own origin', async () => {
    store.seed({ A: 1, B: 2, C: 3 });
    const r = await api.listSkusAt('http://other-host:8080/v2/inventory?limit=1&after=A');
    if (!r.ok) throw new Error();
    expect(r.data).toEqual([{ skuId: 'B', quantity: 2 }]);
  });

  it('requests exactly the Link URL path and query', async () => {
    store.seed({ A: 1, B: 2 });
    await api.listSkusAt('http://other:1/v2/inventory?limit=7&after=A%20b');
    const url = new URL(store.requests.at(-1)?.url ?? '');
    expect(url.pathname + url.search).toBe('/v2/inventory?limit=7&after=A%20b');
  });

  it('gets a SKU with its ETag; "0" and no details property for a SKU without details', async () => {
    store.seed({ A: 1 });
    const r = await api.getSku('A');
    if (!r.ok) throw new Error();
    expect(r.data).toEqual({ skuId: 'A', quantity: 1 });
    expect('details' in r.data).toBe(false);
    expect(r.etag).toBe('"0"');
    store.seedDetails('B', 4, details, 3);
    const r2 = await api.getSku('B');
    if (!r2.ok) throw new Error();
    expect(r2.data.details).toEqual(details);
    expect(r2.etag).toBe('"3"');
  });

  it('returns the text/plain body verbatim on errors', async () => {
    const r = await api.getSku('missing');
    expect(r).toEqual({ ok: false, status: 404, errorText: TEXT.notFound });
  });

  it('falls back to HTTP <status> for an empty error body', async () => {
    server.use(http.get('*/v2/inventory/:skuId', () => new HttpResponse(null, { status: 502 })));
    const r = await api.getSku('A');
    expect(r).toEqual({ ok: false, status: 502, errorText: 'HTTP 502' });
  });
});

describe('client v2 writes', () => {
  const key = '123e4567-e89b-42d3-a456-426614174000';

  it('creates with If-None-Match * and no Idempotency-Key: 201, quantity 0, ETag "1"; the same again is 412 with the server text', async () => {
    const r = await api.putDetails('N-1', details, { ifNoneMatch: '*' });
    expect(r.ok && r.status).toBe(201);
    if (!r.ok) throw new Error();
    expect(r.data).toEqual({ skuId: 'N-1', quantity: 0, details });
    expect(r.etag).toBe('"1"');
    const req = store.requests[0]!;
    expect(req.method).toBe('PUT');
    expect(new URL(req.url).pathname).toBe('/v2/inventory/N-1/details');
    expect(req.headers.get('If-None-Match')).toBe('*');
    expect(req.headers.has('If-Match')).toBe(false);
    expect(req.headers.has('Idempotency-Key')).toBe(false);
    expect(await req.json()).toEqual(details);

    const again = await api.putDetails('N-1', { ...details, name: 'Other' }, { ifNoneMatch: '*' });
    expect(again).toEqual({ ok: false, status: 412, errorText: TEXT.changed });
    expect(store.details.get('N-1')?.details.name).toBe('Linen dress');
    expect(store.items.get('N-1')).toBe(0);
  });

  it('replaces details with If-Match: 200 with a new ETag and the stock kept, 412 on a stale tag or a missing SKU, unconditional without a header', async () => {
    store.seedDetails('E', 2, details);
    const edited: SkuDetails = { ...details, name: 'Linen dress, sand' };
    const r = await api.putDetails('E', edited, { ifMatch: '"1"' });
    if (!r.ok) throw new Error();
    expect(r.status).toBe(200);
    expect(r.data).toEqual({ skuId: 'E', quantity: 2, details: edited });
    expect(r.etag).toBe('"2"');
    const req = store.requests[0]!;
    expect(req.method).toBe('PUT');
    expect(new URL(req.url).pathname).toBe('/v2/inventory/E/details');
    expect(req.headers.get('If-Match')).toBe('"1"');
    expect(req.headers.has('If-None-Match')).toBe(false);

    expect(await api.putDetails('E', details, { ifMatch: '"1"' })).toEqual({ ok: false, status: 412, errorText: TEXT.changed });
    expect(await api.putDetails('nope', details, { ifMatch: '"1"' })).toEqual({ ok: false, status: 412, errorText: TEXT.changed });
    expect(await api.putDetails('nope', details, { ifMatch: '*' })).toEqual({ ok: false, status: 412, errorText: TEXT.changed });
    expect(store.items.has('nope')).toBe(false);
    const unconditional = await api.putDetails('E', details);
    expect(unconditional.ok && unconditional.etag).toBe('"3"');
    expect(store.requests.at(-1)!.headers.has('If-Match')).toBe(false);
  });

  it('mock PUT follows H7: 400 for an invalid id, an invalid body (before the SKU lookup), an empty or malformed If-Match and any If-None-Match but *', async () => {
    store.seedDetails('E', 2, details);
    expect(await api.putDetails('bad id', details)).toEqual({ ok: false, status: 400, errorText: TEXT.invalid });
    expect(await api.putDetails('nope', { name: '' })).toEqual({ ok: false, status: 400, errorText: TEXT.invalid });
    expect(await api.putDetails('E', details, { ifMatch: '' })).toEqual({ ok: false, status: 400, errorText: TEXT.invalid });
    for (const bad of ['1', 'W/"1"', '"1', '"1", x', '* , "1"']) {
      expect(await api.putDetails('E', details, { ifMatch: bad })).toEqual({ ok: false, status: 400, errorText: TEXT.invalid });
    }
    for (const bad of ['"1"', 'W/"1"', '']) {
      expect(await api.putDetails('E', details, { ifNoneMatch: bad })).toEqual({ ok: false, status: 400, errorText: TEXT.invalid });
    }
    expect((await api.putDetails('E', details, { ifMatch: '"0", "1"' })).ok).toBe(true);
    expect(store.details.get('E')?.version).toBe(2);
  });

  it('sends the Idempotency-Key header on add stock (v1)', async () => {
    const r = await api.addStock('A', { quantity: 3 }, key);
    expect(r.ok).toBe(true);
    expect(store.requests[0]?.headers.get('Idempotency-Key')).toBe(key);
    const again = await api.addStock('A', { quantity: 3 }, key);
    if (!again.ok) throw new Error();
    expect(again.data).toEqual({ skuId: 'A', quantity: 3 });
    expect(again.etag).toBeNull();
  });

  it('times out and reports it like a network failure', async () => {
    server.use(
      http.post('*/inventory/:skuId/purchase', async () => {
        await new Promise((r) => setTimeout(r, 300));
        return HttpResponse.json({ skuId: 'A', quantity: 1 });
      }),
    );
    const slow = createClient('http://localhost:3000', { timeoutMs: 50 });
    const r = await slow.purchase('A', { quantity: 1 }, null);
    expect(r.ok).toBe(false);
    if (r.ok) throw new Error();
    expect(r.status).toBe(0);
    expect(r.errorText).toMatch(/timed out/i);
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
