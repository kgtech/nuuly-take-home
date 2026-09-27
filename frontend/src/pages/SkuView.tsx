import { useCallback, useEffect, useId, useState } from 'react';
import { api, type InventoryItem } from '../api/client';
import { StockForm } from '../components/StockForm';
import { ErrorText, Loading } from '../components/Messages';

type State =
  | { phase: 'loading' }
  | { phase: 'error'; status: number; errorText: string }
  | { phase: 'ready'; item: InventoryItem };

/** Rendered with key={skuId} by the router, so a new SKU remounts it and starts loading. */
export function SkuView({ skuId }: { skuId: string }) {
  const id = useId();
  const [state, setState] = useState<State>({ phase: 'loading' });
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let live = true;
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
  }, [skuId, attempt]);

  const retry = () => {
    setState({ phase: 'loading' });
    setAttempt((n) => n + 1);
  };

  const onChange = useCallback((item: InventoryItem) => setState({ phase: 'ready', item }), []);

  return (
    <section>
      <p>
        <a href="#/">← Inventory</a>
      </p>
      <h1>{skuId}</h1>
      {state.phase === 'loading' && <Loading what="SKU" />}
      {state.phase === 'error' && (
        <>
          <ErrorText text={state.errorText} />
          {state.status !== 404 && (
            <button type="button" onClick={retry}>
              Retry
            </button>
          )}
        </>
      )}
      {state.phase === 'ready' && (
        <p className="quantity">
          Quantity: <strong data-testid="quantity">{state.item.quantity}</strong>
        </p>
      )}
      {state.phase !== 'loading' && (
        <div className="forms">
          <section aria-labelledby={`${id}-add`}>
            <h2 id={`${id}-add`}>Add stock</h2>
            {state.phase === 'error' && (
              <p className="muted">Adding stock creates the SKU if it does not exist.</p>
            )}
            <StockForm operation="add" skuId={skuId} onSuccess={onChange} />
          </section>
          <section aria-labelledby={`${id}-buy`}>
            <h2 id={`${id}-buy`}>Purchase</h2>
            <StockForm operation="purchase" skuId={skuId} onSuccess={onChange} />
          </section>
        </div>
      )}
    </section>
  );
}
