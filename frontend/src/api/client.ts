import type { components, operations, paths } from './schema';
import { nextLink } from './link';

export type InventoryItem = components['schemas']['InventoryItem'];
export type InventoryQuantity = components['schemas']['InventoryQuantity'];
export type ListParams = NonNullable<operations['listInventory']['parameters']['query']>;

type ListPath = keyof Pick<paths, '/inventory'>;
type ItemPath = keyof Pick<paths, '/inventory/{skuId}'>;
type PurchasePath = keyof Pick<paths, '/inventory/{skuId}/purchase'>;

type Json<T> = T extends { content: { 'application/json': infer J } } ? J : never;
type ListOk = Json<operations['listInventory']['responses'][200]>;
type GetOk = Json<operations['getInventory']['responses'][200]>;
type CreateOk = Json<operations['createInventory']['responses'][200]>;
type PurchaseOk = Json<operations['purchaseItem']['responses'][200]>;
type IdempotencyHeader = NonNullable<operations['createInventory']['parameters']['header']>;
export const IDEMPOTENCY_KEY = 'Idempotency-Key' satisfies keyof IdempotencyHeader;

/** Status 0 means the request never reached the server (network failure). */
export type ApiFailure = { ok: false; status: number; errorText: string };
export type ApiResult<T> = { ok: true; status: number; data: T; next: string | null } | ApiFailure;

export interface InventoryClient {
  listInventory(params?: ListParams): Promise<ApiResult<ListOk>>;
  /** Fetches a page given by a Link header URL, re-rooted on this client's base. */
  listInventoryAt(url: string): Promise<ApiResult<ListOk>>;
  getInventory(skuId: string): Promise<ApiResult<GetOk>>;
  addStock(skuId: string, body: InventoryQuantity, key: string | null): Promise<ApiResult<CreateOk>>;
  purchase(skuId: string, body: InventoryQuantity, key: string | null): Promise<ApiResult<PurchaseOk>>;
}

const LIST: ListPath = '/inventory';
const ITEM: ItemPath = '/inventory/{skuId}';
const PURCHASE: PurchasePath = '/inventory/{skuId}/purchase';

function itemUrl(template: ItemPath | PurchasePath, skuId: string): string {
  return template.replace('{skuId}', encodeURIComponent(skuId));
}

async function call<T>(url: URL, init: RequestInit): Promise<ApiResult<T>> {
  let res: Response;
  try {
    res = await fetch(url, init);
  } catch (e) {
    const detail = e instanceof Error ? e.message : String(e);
    return { ok: false, status: 0, errorText: `Network error: ${detail}` };
  }
  if (!res.ok) {
    return { ok: false, status: res.status, errorText: await res.text() };
  }
  try {
    const data = (await res.json()) as T;
    return { ok: true, status: res.status, data, next: nextLink(res.headers.get('Link')) };
  } catch {
    return { ok: false, status: res.status, errorText: 'Unreadable response from the server' };
  }
}

function post(body: InventoryQuantity, key: string | null): RequestInit {
  const headers: Record<string, string> = {
    'Content-Type': 'application/json',
    Accept: 'application/json, text/plain',
  };
  if (key !== null) headers[IDEMPOTENCY_KEY] = key;
  return { method: 'POST', headers, body: JSON.stringify(body) };
}

const GET: RequestInit = { method: 'GET', headers: { Accept: 'application/json, text/plain' } };

export function createClient(base: string = window.location.origin): InventoryClient {
  return {
    listInventory(params = {}) {
      const url = new URL(LIST, base);
      if (params.limit !== undefined) url.searchParams.set('limit', String(params.limit));
      if (params.after !== undefined) url.searchParams.set('after', params.after);
      return call<ListOk>(url, GET);
    },
    listInventoryAt(link) {
      const given = new URL(link, base);
      const url = new URL(given.pathname + given.search, base);
      return call<ListOk>(url, GET);
    },
    getInventory(skuId) {
      return call<GetOk>(new URL(itemUrl(ITEM, skuId), base), GET);
    },
    addStock(skuId, body, key) {
      return call<CreateOk>(new URL(itemUrl(ITEM, skuId), base), post(body, key));
    },
    purchase(skuId, body, key) {
      return call<PurchaseOk>(new URL(itemUrl(PURCHASE, skuId), base), post(body, key));
    },
  };
}

export const api: InventoryClient = createClient();
