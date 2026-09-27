import { useEffect, useState } from 'react';

export type Route =
  | { name: 'list' }
  | { name: 'add' }
  | { name: 'sku'; skuId: string }
  | { name: 'notFound'; path: string };

export function parseRoute(hash: string): Route {
  const path = hash.replace(/^#/, '') || '/';
  if (path === '/') return { name: 'list' };
  if (path === '/add') return { name: 'add' };
  const sku = /^\/sku\/(.+)$/.exec(path);
  if (sku?.[1]) return { name: 'sku', skuId: decodeURIComponent(sku[1]) };
  return { name: 'notFound', path };
}

export function skuHref(skuId: string): string {
  return `#/sku/${encodeURIComponent(skuId)}`;
}

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
