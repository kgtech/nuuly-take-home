import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import type { components } from '../api/schema';

type Item = components['schemas']['InventoryItem'];
type Body = components['schemas']['InventoryQuantity'];
type SkuItem = components['schemas']['SkuItem'];
type SkuDetails = components['schemas']['SkuDetails'];

export const TEXT = {
  notFound: 'SKU not found',
  insufficient: 'Insufficient inventory',
  invalid: 'Invalid request',
  changed: 'Details changed since you read them. Reload the SKU and retry with its new ETag.',
} as const;

const SKU = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
const UUID = /^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$/;
const CURRENCY = /^[A-Z]{3}$/;
const MAX_PAGE = 250;

/**
 * In-memory store mocking the contract: v1 stock (ordering, Link paging, Idempotency-Key
 * replay) and v2 details (ETag versions, PUT details with If-Match and If-None-Match: *, 412 on a failed precondition).
 */
export const store = {
  items: new Map<string, number>(),
  details: new Map<string, { details: SkuDetails; version: number }>(),
  keys: new Map<string, { hash: string; status: number; body: string; contentType: string; etag?: string }>(),
  requests: [] as Request[],
  reset() {
    this.items.clear();
    this.details.clear();
    this.keys.clear();
    this.requests = [];
  },
  seed(entries: Record<string, number>) {
    for (const [k, v] of Object.entries(entries)) this.items.set(k, v);
  },
  /** Seeds a SKU with details at version 1 (or the given one). */
  seedDetails(skuId: string, quantity: number, details: SkuDetails, version = 1) {
    this.items.set(skuId, quantity);
    this.details.set(skuId, { details, version });
  },
  etag(skuId: string): string {
    return `"${this.details.get(skuId)?.version ?? 0}"`;
  },
};

const text = (status: number, body: string) =>
  new HttpResponse(body, { status, headers: { 'Content-Type': 'text/plain' } });

function skuItem(skuId: string): SkuItem {
  const item: SkuItem = { skuId, quantity: store.items.get(skuId) ?? 0 };
  const d = store.details.get(skuId);
  if (d) item.details = d.details;
  return item;
}

const withEtag = (skuId: string, body: SkuItem, status = 200) =>
  HttpResponse.json(body, { status, headers: { ETag: store.etag(skuId) } });

function sortedIds(): string[] {
  return [...store.items.keys()].sort((a, b) => (a < b ? -1 : a > b ? 1 : 0));
}

function page(url: URL, path: '/inventory' | '/v2/inventory'): Response {
  const afterAll = url.searchParams.getAll('after');
  if (afterAll.length > 1) return text(400, TEXT.invalid);
  const after = afterAll[0] ?? null;
  const rawLimit = url.searchParams.get('limit');
  let limit = MAX_PAGE;
  if (rawLimit !== null && /^\d+$/.test(rawLimit) && Number(rawLimit) >= 1) {
    limit = Math.min(Number(rawLimit), MAX_PAGE);
  }
  const ids = sortedIds();
  const rest = after === null ? ids : ids.filter((id) => id > after);
  const slice = rest.slice(0, limit);
  const body: (Item | SkuItem)[] =
    path === '/inventory'
      ? slice.map((id) => ({ skuId: id, quantity: store.items.get(id) ?? 0 }))
      : slice.map(skuItem);
  const headers: Record<string, string> = {};
  if (rest.length > limit) {
    const last = slice[slice.length - 1];
    const next = new URL(url.origin + path);
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
    const replay = replayFor(key, hash);
    if (replay) return replay;
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

/** The stored response for a reused key, 400 for a different request, or null for a fresh key. */
function replayFor(key: string, hash: string): Response | null {
  const stored = store.keys.get(key);
  if (!stored) return null;
  if (stored.hash !== hash) return text(400, TEXT.invalid);
  const headers: Record<string, string> = { 'Content-Type': stored.contentType };
  if (stored.etag) headers.ETag = stored.etag;
  return new HttpResponse(stored.body, { status: stored.status, headers });
}

// The service's SkuDetails record (F-01, F-03): Java isBlank (NBSP is not blank), no control characters
// (a description may hold \n and \t), no unpaired surrogate, and an image URL that java.net.URI parses
// with a lowercase http/https scheme, a non-null host and an ASCII round-trip.
// eslint-disable-next-line no-control-regex -- mirrors the server (F-03)
const JAVA_BLANK = /^[\t\n\v\f\r\x1C-\x1F \u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]*$/;
// eslint-disable-next-line no-control-regex -- mirrors the server (F-03)
const NAME_CONTROL = /[\x00-\x1F\x7F\p{Cs}]/u;
// eslint-disable-next-line no-control-regex -- mirrors the server (F-03)
const DESCRIPTION_CONTROL = /[\x00-\x08\x0B-\x1F\x7F\p{Cs}]/u;
const URI_REJECTS = /[^\x21-\x7E]|["<>\\^`{|}]/;
const IMAGE_URL =
  /^https?:\/\/(?:[!$&'()*+,;=A-Za-z0-9._~%:-]*@)?(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+(?:%[A-Za-z0-9]+)?\])(?::\d*)?(?:[/?#][!-~]*)?$/;

/** The server's field rules (DESIGN-V2 §8); returns the normalised details or null when invalid. */
function validDetails(input: unknown): SkuDetails | null {
  if (typeof input !== 'object' || input === null) return null;
  const d = input as Record<string, unknown>;
  if (typeof d.name !== 'string' || JAVA_BLANK.test(d.name) || d.name.length > 120 || NAME_CONTROL.test(d.name)) {
    return null;
  }
  const out: SkuDetails = { name: d.name, description: '', images: [] };
  if (d.description !== undefined) {
    if (typeof d.description !== 'string' || d.description.length > 2000 || DESCRIPTION_CONTROL.test(d.description)) {
      return null;
    }
    out.description = d.description;
  }
  if (d.images !== undefined) {
    if (!Array.isArray(d.images) || d.images.length > 10) return null;
    for (const u of d.images) {
      if (typeof u !== 'string' || u.length > 2048 || URI_REJECTS.test(u) || !IMAGE_URL.test(u)) return null;
    }
    out.images = d.images as string[];
  }
  if (d.cost !== undefined && d.cost !== null) {
    const c = d.cost as Record<string, unknown>;
    if (typeof c.amount !== 'number' || !Number.isInteger(c.amount) || c.amount < 0) return null;
    if (typeof c.currency !== 'string' || !CURRENCY.test(c.currency)) return null;
    out.cost = { amount: c.amount, currency: c.currency };
  }
  return out;
}

/** H7: PUT /v2/inventory/{skuId}/details creates (201, quantity 0) or replaces (200); body, skuId, preconditions, write. */
async function putDetails(request: Request, skuId: string): Promise<Response> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return text(400, TEXT.invalid);
  }
  const details = validDetails(body);
  if (details === null) return text(400, TEXT.invalid);
  if (!SKU.test(skuId)) return text(400, TEXT.invalid);
  // An empty or malformed If-Match (not "*" and not a list of strong ETags) is 400, and so is any If-None-Match but "*".
  const ifMatch = request.headers.get('If-Match');
  let tags: string[] | null = null;
  if (ifMatch !== null && ifMatch.trim() !== '*') {
    tags = ifMatch.split(',').map((t) => t.trim());
    if (tags.some((t) => !/^"[^"]*"$/.test(t))) return text(400, TEXT.invalid);
  }
  const ifNoneMatch = request.headers.get('If-None-Match');
  if (ifNoneMatch !== null && ifNoneMatch.trim() !== '*') return text(400, TEXT.invalid);

  const exists = store.items.has(skuId);
  if (ifMatch !== null && (!exists || (tags !== null && !tags.includes(store.etag(skuId))))) return text(412, TEXT.changed);
  if (ifNoneMatch !== null && exists) return text(412, TEXT.changed);
  const current = store.details.get(skuId);
  if (!exists) store.items.set(skuId, 0);
  store.details.set(skuId, { details, version: (current?.version ?? 0) + 1 });
  return withEtag(skuId, skuItem(skuId), exists ? 200 : 201);
}

export const handlers = [
  // v2 first: MSW's "*" also matches "/v2", so the v1 patterns below would otherwise catch these.
  http.get('*/v2/inventory', ({ request }) => page(new URL(request.url), '/v2/inventory')),
  http.get('*/v2/inventory/:skuId', ({ params }) => {
    const skuId = String(params.skuId);
    if (!SKU.test(skuId) || !store.items.has(skuId)) return text(404, TEXT.notFound);
    return withEtag(skuId, skuItem(skuId));
  }),
  http.put('*/v2/inventory/:skuId/details', ({ request, params }) => putDetails(request, String(params.skuId))),

  http.get('*/inventory', ({ request }) => page(new URL(request.url), '/inventory')),
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
