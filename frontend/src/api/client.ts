import type { components, operations, paths } from './schema';
import { nextLink } from './link';

export type InventoryItem = components['schemas']['InventoryItem'];
export type InventoryQuantity = components['schemas']['InventoryQuantity'];
export type SkuItem = components['schemas']['SkuItem'];
export type SkuDetails = components['schemas']['SkuDetails'];
export type SkuCost = components['schemas']['SkuCost'];
export type CreateSkuRequest = components['schemas']['CreateSkuRequest'];
export type ListParams = NonNullable<operations['listSkus']['parameters']['query']>;

type ItemPath = keyof Pick<paths, '/inventory/{skuId}'>;
type PurchasePath = keyof Pick<paths, '/inventory/{skuId}/purchase'>;
type SkuListPath = keyof Pick<paths, '/v2/inventory'>;
type SkuPath = keyof Pick<paths, '/v2/inventory/{skuId}'>;

type Json<T> = T extends { content: { 'application/json': infer J } } ? J : never;
type AddOk = Json<operations['createInventory']['responses'][200]>;
type PurchaseOk = Json<operations['purchaseItem']['responses'][200]>;
type ListOk = Json<operations['listSkus']['responses'][200]>;
type GetOk = Json<operations['getSku']['responses'][200]>;
type CreateOk = Json<operations['createSku']['responses'][201]>;
type ReplaceOk = Json<operations['replaceSkuDetails']['responses'][200]>;
type IdempotencyHeader = NonNullable<operations['createInventory']['parameters']['header']>;
type IfMatchHeader = NonNullable<operations['replaceSkuDetails']['parameters']['header']>;
export const IDEMPOTENCY_KEY = 'Idempotency-Key' satisfies keyof IdempotencyHeader;
export const IF_MATCH = 'If-Match' satisfies keyof IfMatchHeader;

/** Status 0 means the request never reached the server (network failure). */
export type ApiFailure = { ok: false; status: number; errorText: string };
/** `etag` is the response's ETag (v2 reads and writes), null when the response has none. */
export type ApiResult<T> =
  | { ok: true; status: number; data: T; next: string | null; etag: string | null }
  | ApiFailure;

export interface InventoryClient {
  /** GET /v2/inventory: one page of SKUs with details; `next` is the Link rel="next" URL. */
  listSkus(params?: ListParams): Promise<ApiResult<ListOk>>;
  /** Fetches a page given by a Link header URL, re-rooted on this client's base. */
  listSkusAt(url: string): Promise<ApiResult<ListOk>>;
  /** GET /v2/inventory/{skuId}: the SKU with its details and ETag. */
  getSku(skuId: string): Promise<ApiResult<GetOk>>;
  /** POST /v2/inventory/{skuId}: 201 with the created SKU and its ETag; 409 when it exists. */
  createSku(skuId: string, body: CreateSkuRequest, key: string | null): Promise<ApiResult<CreateOk>>;
  /** PUT /v2/inventory/{skuId} with If-Match when given: 200, 404, or 412 on a stale tag. */
  replaceSkuDetails(skuId: string, body: SkuDetails, ifMatch: string | null): Promise<ApiResult<ReplaceOk>>;
  /** POST /inventory/{skuId} (v1): add stock, creating the SKU. */
  addStock(skuId: string, body: InventoryQuantity, key: string | null): Promise<ApiResult<AddOk>>;
  /** POST /inventory/{skuId}/purchase (v1). */
  purchase(skuId: string, body: InventoryQuantity, key: string | null): Promise<ApiResult<PurchaseOk>>;
}

const ITEM: ItemPath = '/inventory/{skuId}';
const PURCHASE: PurchasePath = '/inventory/{skuId}/purchase';
const SKU_LIST: SkuListPath = '/v2/inventory';
const SKU: SkuPath = '/v2/inventory/{skuId}';

function itemUrl(template: ItemPath | PurchasePath | SkuPath, skuId: string): string {
  return template.replace('{skuId}', encodeURIComponent(skuId));
}

export interface ClientOptions {
  /** Abort a request after this long and report it as a network failure (status 0). */
  timeoutMs?: number;
}
const DEFAULT_TIMEOUT_MS = 15_000;

async function call<T>(url: URL, init: RequestInit, timeoutMs: number): Promise<ApiResult<T>> {
  let res: Response;
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), timeoutMs);
  try {
    res = await fetch(url, { ...init, signal: controller.signal });
  } catch (e) {
    if (controller.signal.aborted) {
      return { ok: false, status: 0, errorText: `Network error: the request timed out after ${timeoutMs / 1000} s` };
    }
    const detail = e instanceof Error ? e.message : String(e);
    return { ok: false, status: 0, errorText: `Network error: ${detail}` };
  } finally {
    clearTimeout(timer);
  }
  if (!res.ok) {
    const body = await res.text();
    return { ok: false, status: res.status, errorText: body === '' ? `HTTP ${res.status}` : body };
  }
  try {
    const data = (await res.json()) as T;
    return {
      ok: true,
      status: res.status,
      data,
      next: nextLink(res.headers.get('Link')),
      etag: res.headers.get('ETag'),
    };
  } catch {
    return { ok: false, status: res.status, errorText: 'Unreadable response from the server' };
  }
}

const JSON_HEADERS = { 'Content-Type': 'application/json', Accept: 'application/json, text/plain' } as const;

function post(body: unknown, key: string | null): RequestInit {
  const headers: Record<string, string> = { ...JSON_HEADERS };
  if (key !== null) headers[IDEMPOTENCY_KEY] = key;
  return { method: 'POST', headers, body: JSON.stringify(body) };
}

function put(body: unknown, ifMatch: string | null): RequestInit {
  const headers: Record<string, string> = { ...JSON_HEADERS };
  if (ifMatch !== null) headers[IF_MATCH] = ifMatch;
  return { method: 'PUT', headers, body: JSON.stringify(body) };
}

const GET: RequestInit = { method: 'GET', headers: { Accept: 'application/json, text/plain' } };

export function createClient(base: string = window.location.origin, options: ClientOptions = {}): InventoryClient {
  const t = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  return {
    listSkus(params = {}) {
      const url = new URL(SKU_LIST, base);
      if (params.limit !== undefined) url.searchParams.set('limit', String(params.limit));
      if (params.after !== undefined) url.searchParams.set('after', params.after);
      return call<ListOk>(url, GET, t);
    },
    listSkusAt(link) {
      const given = new URL(link, base);
      const url = new URL(given.pathname + given.search, base);
      return call<ListOk>(url, GET, t);
    },
    getSku(skuId) {
      return call<GetOk>(new URL(itemUrl(SKU, skuId), base), GET, t);
    },
    createSku(skuId, body, key) {
      return call<CreateOk>(new URL(itemUrl(SKU, skuId), base), post(body, key), t);
    },
    replaceSkuDetails(skuId, body, ifMatch) {
      return call<ReplaceOk>(new URL(itemUrl(SKU, skuId), base), put(body, ifMatch), t);
    },
    addStock(skuId, body, key) {
      return call<AddOk>(new URL(itemUrl(ITEM, skuId), base), post(body, key), t);
    },
    purchase(skuId, body, key) {
      return call<PurchaseOk>(new URL(itemUrl(PURCHASE, skuId), base), post(body, key), t);
    },
  };
}

export const api: InventoryClient = createClient();
