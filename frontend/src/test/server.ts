import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { components } from '../api/schema';

type Item = components['schemas']['InventoryItem'];
type Body = components['schemas']['InventoryQuantity'];

export const TEXT = {
  notFound: 'SKU not found',
  insufficient: 'Insufficient inventory',
  invalid: 'Invalid request',
} as const;

const SKU = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const UUID = /^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$/;
const MAX_PAGE = 250;

/** In-memory store mocking the contract, including Link paging and Idempotency-Key replay. */
export const store = {
  items: new Map<string, number>(),
  keys: new Map<string, { hash: string; status: number; body: string; contentType: string }>(),
  requests: [] as Request[],
  reset() {
    this.items.clear();
    this.keys.clear();
    this.requests = [];
  },
  seed(entries: Record<string, number>) {
    for (const [k, v] of Object.entries(entries)) this.items.set(k, v);
  },
};

const text = (status: number, body: string) =>
  new HttpResponse(body, { status, headers: { 'Content-Type': 'text/plain' } });

function page(url: URL): Response {
  const afterAll = url.searchParams.getAll('after');
  if (afterAll.length > 1) return text(400, TEXT.invalid);
  const after = afterAll[0] ?? null;
  const rawLimit = url.searchParams.get('limit');
  let limit = MAX_PAGE;
  if (rawLimit !== null && /^\d+$/.test(rawLimit) && Number(rawLimit) >= 1) {
    limit = Math.min(Number(rawLimit), MAX_PAGE);
  }
  const ids = [...store.items.keys()].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
  const rest = after === null ? ids : ids.filter((id) => id > after);
  const slice = rest.slice(0, limit);
  const body: Item[] = slice.map((id) => ({ skuId: id, quantity: store.items.get(id) ?? 0 }));
  const headers: Record<string, string> = {};
  if (rest.length > limit) {
    const last = slice[slice.length - 1];
    const next = new URL(url.origin + '/inventory');
    next.searchParams.set('limit', String(limit));
    next.searchParams.set('after', last ?? '');
    headers.Link = `<${next.toString()}>; rel="next"`;
  }
  return HttpResponse.json(body, { headers });
}

async function write(
  request: Request,
  skuId: string,
  op: 'add' | 'purchase',
): Promise<Response> {
  let body: Body;
  try {
    body = (await request.json()) as Body;
  } catch {
    return text(400, TEXT.invalid);
  }
  if (typeof body.quantity !== 'number' || !Number.isInteger(body.quantity) || body.quantity < 1) {
    return text(400, TEXT.invalid);
  }
  const key = request.headers.get('Idempotency-Key');
  if (key !== null && !UUID.test(key)) return text(400, TEXT.invalid);
  if (!SKU.test(skuId)) return op === 'add' ? text(400, TEXT.invalid) : text(404, TEXT.notFound);

  const hash = `${op}\n${skuId}\n${body.quantity}`;
  if (key !== null) {
    const stored = store.keys.get(key);
    if (stored) {
      if (stored.hash !== hash) return text(400, TEXT.invalid);
      return new HttpResponse(stored.body, {
        status: stored.status,
        headers: { 'Content-Type': stored.contentType },
      });
    }
  }
  let status: number;
  let out: string;
  let contentType: string;
  const current = store.items.get(skuId);
  if (op === 'add') {
    const next = (current ?? 0) + body.quantity;
    store.items.set(skuId, next);
    status = 200;
    out = JSON.stringify({ skuId, quantity: next } satisfies Item);
    contentType = 'application/json';
  } else if (current === undefined) {
    status = 404;
    out = TEXT.notFound;
    contentType = 'text/plain';
  } else if (current < body.quantity) {
    status = 400;
    out = TEXT.insufficient;
    contentType = 'text/plain';
  } else {
    store.items.set(skuId, current - body.quantity);
    status = 200;
    out = JSON.stringify({ skuId, quantity: current - body.quantity } satisfies Item);
    contentType = 'application/json';
  }
  if (key !== null) store.keys.set(key, { hash, status, body: out, contentType });
  return new HttpResponse(out, { status, headers: { 'Content-Type': contentType } });
}

export const handlers = [
  http.get('*/inventory', ({ request }) => page(new URL(request.url))),
  http.get('*/inventory/:skuId', ({ params }) => {
    const skuId = String(params.skuId);
    const q = store.items.get(skuId);
    if (!SKU.test(skuId) || q === undefined) return text(404, TEXT.notFound);
    return HttpResponse.json({ skuId, quantity: q } satisfies Item);
  }),
  http.post('*/inventory/:skuId/purchase', ({ request, params }) =>
    write(request, String(params.skuId), 'purchase'),
  ),
  http.post('*/inventory/:skuId', ({ request, params }) =>
    write(request, String(params.skuId), 'add'),
  ),
];

export const server = setupServer(...handlers);
server.events.on('request:start', ({ request }) => {
  store.requests.push(request.clone());
});
