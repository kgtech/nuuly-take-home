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

  it('creates a SKU with details and initial stock: 201 with the ETag, then 409 on the same id without the key', async () => {
    const r = await api.createSku('N-1', { details, initialQuantity: 5 }, key);
    expect(r.ok && r.status).toBe(201);
    if (!r.ok) throw new Error();
    expect(r.data).toEqual({ skuId: 'N-1', quantity: 5, details });
    expect(r.etag).toBe('"1"');
    const req = store.requests[0]!;
    expect(req.method).toBe('POST');
    expect(new URL(req.url).pathname).toBe('/v2/inventory/N-1');
    expect(req.headers.get('Idempotency-Key')).toBe(key);
    expect(await req.json()).toEqual({ details, initialQuantity: 5 });

    const replay = await api.createSku('N-1', { details, initialQuantity: 5 }, key);
    expect(replay.ok && replay.status).toBe(201);
    const again = await api.createSku('N-1', { details, initialQuantity: 5 }, null);
    expect(again).toEqual({ ok: false, status: 409, errorText: TEXT.exists });
    expect(store.items.get('N-1')).toBe(5);
  });

  it('replaces details with If-Match: 200 with a new ETag, 412 on a stale tag, 404 for a missing SKU', async () => {
    store.seedDetails('E', 2, details);
    const edited: SkuDetails = { ...details, name: 'Linen dress, sand' };
    const r = await api.replaceSkuDetails('E', edited, '"1"');
    if (!r.ok) throw new Error();
    expect(r.data).toEqual({ skuId: 'E', quantity: 2, details: edited });
    expect(r.etag).toBe('"2"');
    const req = store.requests[0]!;
    expect(req.method).toBe('PUT');
    expect(req.headers.get('If-Match')).toBe('"1"');

    const stale = await api.replaceSkuDetails('E', details, '"1"');
    expect(stale).toEqual({ ok: false, status: 412, errorText: TEXT.changed });
    const missing = await api.replaceSkuDetails('nope', details, null);
    expect(missing).toEqual({ ok: false, status: 404, errorText: TEXT.notFound });
    const unconditional = await api.replaceSkuDetails('E', details, null);
    expect(unconditional.ok && unconditional.etag).toBe('"3"');
    expect(store.requests.at(-1)!.headers.has('If-Match')).toBe(false);
  });

  it('mock PUT validates the body before the SKU lookup and refuses an empty If-Match, as the service does (F-fe-06)', async () => {
    store.seedDetails('E', 2, details);
    const badBodyMissingSku = await api.replaceSkuDetails('nope', { name: '' }, null);
    expect(badBodyMissingSku).toEqual({ ok: false, status: 400, errorText: TEXT.invalid });
    const emptyIfMatch = await api.replaceSkuDetails('E', details, '');
    expect(emptyIfMatch).toEqual({ ok: false, status: 400, errorText: TEXT.invalid });
    expect(store.details.get('E')?.version).toBe(1);
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
