import type { components, operations, paths } from './schema';
import { nextLink } from './link';

export type InventoryQuantity = components['schemas']['InventoryQuantity'];
export type SkuItem = components['schemas']['SkuItem'];
export type SkuDetails = components['schemas']['SkuDetails'];
export type SkuCost = components['schemas']['SkuCost'];
export type ListParams = NonNullable<operations['listSkus']['parameters']['query']>;

type PurchasePath = keyof Pick<paths, '/v2/inventory/{skuId}/purchase'>;
type SkuListPath = keyof Pick<paths, '/v2/inventory'>;
type SkuPath = keyof Pick<paths, '/v2/inventory/{skuId}'>;
type DetailsPath = keyof Pick<paths, '/v2/inventory/{skuId}/details'>;

type Json<T> = T extends { content: { 'application/json': infer J } } ? J : never;
type AddOk = Json<operations['addStock']['responses'][200]>;
type PurchaseOk = Json<operations['purchaseStock']['responses'][200]>;
type ListOk = Json<operations['listSkus']['responses'][200]>;
type GetOk = Json<operations['getSku']['responses'][200]>;
type DetailsOk = Json<operations['putSkuDetails']['responses'][200]>;
type IdempotencyHeader = NonNullable<operations['addStock']['parameters']['header']>;
type DetailsHeader = NonNullable<operations['putSkuDetails']['parameters']['header']>;
export const IDEMPOTENCY_KEY = 'Idempotency-Key' satisfies keyof IdempotencyHeader;
export const IF_MATCH = 'If-Match' satisfies keyof DetailsHeader;
export const IF_NONE_MATCH = 'If-None-Match' satisfies keyof DetailsHeader;

export interface DetailsPreconditions {
  ifMatch?: string;
  ifNoneMatch?: string;
}

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
  /**
   * PUT /v2/inventory/{skuId}/details: replaces the details. 201 creates the SKU at stock 0, 200 replaces;
   * `ifNoneMatch: '*'` makes it create-only and `ifMatch` makes it a compare-and-swap, both 412 when they fail.
   */
  putDetails(skuId: string, body: SkuDetails, preconditions?: DetailsPreconditions): Promise<ApiResult<DetailsOk>>;
  /** POST /v2/inventory/{skuId}: add stock (creating the SKU); the key is required. */
  addStock(skuId: string, body: InventoryQuantity, key: string): Promise<ApiResult<AddOk>>;
  /** POST /v2/inventory/{skuId}/purchase; the key is required. */
  purchase(skuId: string, body: InventoryQuantity, key: string): Promise<ApiResult<PurchaseOk>>;
}

const PURCHASE: PurchasePath = '/v2/inventory/{skuId}/purchase';
const SKU_LIST: SkuListPath = '/v2/inventory';
const SKU: SkuPath = '/v2/inventory/{skuId}';
const DETAILS: DetailsPath = '/v2/inventory/{skuId}/details';

function itemUrl(template: PurchasePath | SkuPath | DetailsPath, skuId: string): string {
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

function post(body: unknown, key: string): RequestInit {
  const headers: Record<string, string> = { ...JSON_HEADERS };
  if (key !== null) headers[IDEMPOTENCY_KEY] = key;
  return { method: 'POST', headers, body: JSON.stringify(body) };
}

function put(body: unknown, { ifMatch, ifNoneMatch }: DetailsPreconditions): RequestInit {
  const headers: Record<string, string> = { ...JSON_HEADERS };
  if (ifMatch !== undefined) headers[IF_MATCH] = ifMatch;
  if (ifNoneMatch !== undefined) headers[IF_NONE_MATCH] = ifNoneMatch;
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
    putDetails(skuId, body, preconditions = {}) {
      return call<DetailsOk>(new URL(itemUrl(DETAILS, skuId), base), put(body, preconditions), t);
    },
    addStock(skuId, body, key) {
      return call<AddOk>(new URL(itemUrl(SKU, skuId), base), post(body, key), t);
    },
    purchase(skuId, body, key) {
      return call<PurchaseOk>(new URL(itemUrl(PURCHASE, skuId), base), post(body, key), t);
    },
  };
}

export const api: InventoryClient = createClient();
