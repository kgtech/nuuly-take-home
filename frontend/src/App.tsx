import { useEffect, useRef } from 'react';
import { useHashRoute } from './hooks/useHashRoute';
import { InventoryList } from './pages/InventoryList';
import { SkuView } from './pages/SkuView';
import { AddStockPage } from './pages/AddStockPage';
import { CreateSkuPage } from './pages/CreateSkuPage';
import { EditSkuPage } from './pages/EditSkuPage';

import { BRAND } from './brand';

export function App() {
  const route = useHashRoute();
  const mainRef = useRef<HTMLElement>(null);
  const first = useRef(true);

  useEffect(() => {
    if (first.current) {
      first.current = false;
      return;
    }
    mainRef.current?.focus();
  }, [route]);

  const current = (name: string) => (route.name === name ? 'page' : undefined);

  return (
    <>
      <header className="site-header">
        <div className="bar">
          <a href="#/" className="brand">
            {BRAND}
          </a>
          <nav aria-label="Main">
            <a href="#/" aria-current={current('list')}>
              Inventory
            </a>
            <a href="#/new" aria-current={current('new')}>
              New SKU
            </a>
            <a href="#/add" aria-current={current('add')}>
              Add stock
            </a>
          </nav>
        </div>
      </header>
      <main ref={mainRef} tabIndex={-1} className={route.name === 'add' || route.name === 'new' || route.name === 'edit' ? 'narrow' : undefined}>
        {route.name === 'list' && <InventoryList />}
        {route.name === 'add' && <AddStockPage />}
        {route.name === 'new' && <CreateSkuPage />}
        {route.name === 'sku' && <SkuView key={route.skuId} skuId={route.skuId} />}
        {route.name === 'edit' && <EditSkuPage key={route.skuId} skuId={route.skuId} />}
        {route.name === 'notFound' && (
          <section className="page-head">
            <h1>Page not found</h1>
            <p className="subtitle">
              No page at <code>{route.path}</code>. Go to the <a href="#/">inventory</a>.
            </p>
          </section>
        )}
      </main>
    </>
  );
}
