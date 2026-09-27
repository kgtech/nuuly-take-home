import { useEffect, useRef } from 'react';
import { useHashRoute } from './hooks/useHashRoute';
import { InventoryList } from './pages/InventoryList';
import { SkuView } from './pages/SkuView';
import { AddStockPage } from './pages/AddStockPage';
import { FindSku } from './components/FindSku';

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

  return (
    <>
      <header>
        <nav aria-label="Main">
          <a href="#/">Inventory</a>
          <a href="#/add">Add stock</a>
        </nav>
      </header>
      <main ref={mainRef} tabIndex={-1}>
        {route.name === 'list' && (
          <>
            <FindSku />
            <InventoryList />
          </>
        )}
        {route.name === 'add' && <AddStockPage />}
        {route.name === 'sku' && <SkuView key={route.skuId} skuId={route.skuId} />}
        {route.name === 'notFound' && (
          <section>
            <h1>Page not found</h1>
            <p>
              No page at <code>{route.path}</code>. Go to the <a href="#/">inventory</a>.
            </p>
          </section>
        )}
      </main>
    </>
  );
}
