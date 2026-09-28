import { useCallback, useEffect, useId, useState } from 'react';
import { api, type InventoryItem } from '../api/client';
import { StockForm } from '../components/StockForm';
import { ErrorText, Loading } from '../components/Messages';
import { ArrowLeft, PlusCircle, ShoppingBag } from '../components/Icons';
import { SKU_ID_PATTERN, SKU_NOT_FOUND } from '../validation';

type State =
  | { phase: 'loading' }
  | { phase: 'error'; status: number; errorText: string }
  | { phase: 'ready'; item: InventoryItem };

/** Rendered with key={skuId} by the router, so a new SKU remounts it and starts loading. */
export function SkuView({ skuId }: { skuId: string }) {
  const id = useId();
  const [loaded, setState] = useState<State>({ phase: 'loading' });
  // An id the server is certain to reject (G11) is answered locally with its text; this also
  // keeps "." and ".." off the wire, where URL normalisation would hit another endpoint.
  const invalid = !SKU_ID_PATTERN.test(skuId);
  const state: State = invalid ? { phase: 'error', status: 404, errorText: SKU_NOT_FOUND } : loaded;
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
    if (invalid) return;
    void api.getInventory(skuId).then((r) => {
      if (!live) return;
      setState(
        r.ok
          ? { phase: 'ready', item: r.data }
          : { phase: 'error', status: r.status, errorText: r.errorText },
      );
    });
    return () => {
      live = false;
    };
  }, [skuId, attempt, invalid]);

  const retry = () => {
    setState({ phase: 'loading' });
    setAttempt((n) => n + 1);
  };

  const onChange = useCallback((item: InventoryItem) => setState({ phase: 'ready', item }), []);

  return (
    <section className="page">
      <p style={{ margin: 0 }}>
        <a href="#/" className="back">
          <ArrowLeft />
          Back to inventory
        </a>
      </p>
      <div className="sku-grid">
        <div className="page-head">
          <p className="kicker">SKU</p>
          <h1>{skuId}</h1>
        </div>
        <div className="on-hand">
          {state.phase === 'loading' && <Loading what="SKU" />}
          {state.phase === 'error' && (
            <div className="stack">
              <ErrorText text={state.errorText} />
              {state.status !== 404 && (
                <button type="button" className="secondary" onClick={retry}>
                  Retry
                </button>
              )}
            </div>
          )}
          {state.phase === 'ready' && (
            <>
              <span className="label">On hand</span>
              <div className="row">
                <span className="big" data-testid="quantity">
                  {state.item.quantity}
                </span>
                {(state.item.quantity ?? 0) === 0 && <span className="badge out">Rented out</span>}
              </div>
            </>
          )}
        </div>
      </div>
      {state.phase !== 'loading' && (
        <div className="cards forms">
          <section className="card" aria-labelledby={`${id}-add`}>
            <h2 id={`${id}-add`}>
              <PlusCircle />
              Add stock
            </h2>
            {state.phase === 'error' && (
              <p className="note">Adding stock creates the SKU if it does not exist.</p>
            )}
            <StockForm operation="add" skuId={skuId} onSuccess={onChange} />
          </section>
          <section className="card" aria-labelledby={`${id}-buy`}>
            <h2 id={`${id}-buy`}>
              <ShoppingBag />
              Purchase
            </h2>
            <StockForm operation="purchase" skuId={skuId} onSuccess={onChange} />
          </section>
        </div>
      )}
    </section>
  );
}
