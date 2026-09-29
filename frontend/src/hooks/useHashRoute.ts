import { useEffect, useState } from 'react';

export type Route =
  | { name: 'list' }
  | { name: 'add' }
  | { name: 'new' }
  | { name: 'sku'; skuId: string }
  | { name: 'edit'; skuId: string }
  | { name: 'notFound'; path: string };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '') || '/';
  if (path === '/') return { name: 'list' };
  if (path === '/add') return { name: 'add' };
  if (path === '/new') return { name: 'new' };
  // An id never contains '/', raw or as %2F, so a trailing slash or an extra segment is not a SKU route (M-40).
  const edit = /^\/sku\/([^/]+)\/edit$/.exec(path);
  const sku = /^\/sku\/([^/]+)$/.exec(path);
  const match = edit ?? sku;
  if (match?.[1]) {
    const skuId = safeDecode(match[1]);
    if (!skuId.includes('/')) return { name: edit ? 'edit' : 'sku', skuId };
  }
  return { name: 'notFound', path };
}

/** A malformed escape (e.g. 50%off) keeps the raw segment; the server answers for it. */
function safeDecode(segment: string): string {
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

export function skuHref(skuId: string): string {
  return `#/sku/${encodeURIComponent(skuId)}`;
}

export function editHref(skuId: string): string {
  return `${skuHref(skuId)}/edit`;
}

export const NEW_HREF = '#/new';

export function navigate(hash: string): void {
  window.location.hash = hash;
}

/** Hash routing: no server config, works behind any static host and the Vite proxy. */
export function useHashRoute(): Route {
  const [route, setRoute] = useState(() => parseRoute(window.location.hash));
  useEffect(() => {
    const onChange = () => setRoute(parseRoute(window.location.hash));
    window.addEventListener('hashchange', onChange);
    return () => window.removeEventListener('hashchange', onChange);
  }, []);
  return route;
}
