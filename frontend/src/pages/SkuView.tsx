import { useCallback, useEffect, useId, useState } from 'react';
import { api, type InventoryItem } from '../api/client';
import { StockForm, StockOutcomeView, type StockOutcome } from '../components/StockForm';
import { ErrorText, Loading } from '../components/Messages';
import { ArrowLeft, PlusCircle, ShoppingBag } from '../components/Icons';
import { INSUFFICIENT_INVENTORY, SKU_ID_PATTERN, SKU_NOT_FOUND } from '../validation';
import { availability } from './InventoryList';

type State =
  | { phase: 'loading' }
  | { phase: 'error'; status: number; errorText: string }
  | { phase: 'ready'; item: InventoryItem };

export const ADD_FIRST = 'Add stock first to create this SKU.';
const onHandLine = (n: number) => `Only ${n.toLocaleString()} on hand now. Lower the quantity or add stock.`;

/** Rendered with key={skuId} by the router, so a new SKU remounts it and starts loading. */
export function SkuView({ skuId }: { skuId: string }) {
  const id = useId();
  const [loaded, setState] = useState<State>({ phase: 'loading' });
  // An id the server is certain to reject (G11) is answered locally with its text; this also
  // keeps "." and ".." off the wire, where URL normalisation would hit another endpoint.
  const invalid = !SKU_ID_PATTERN.test(skuId);
  const state: State = invalid ? { phase: 'error', status: 404, errorText: SKU_NOT_FOUND } : loaded;
  const [attempt, setAttempt] = useState(0);
  // One outcome area for both forms (FE32); `extra` is the on-hand line after "Insufficient inventory".
  const [outcome, setOutcome] = useState<{ outcome: StockOutcome; extra?: string } | null>(null);

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

  const onOutcome = useCallback(
    (o: StockOutcome) => {
      if (o.kind === 'done') {
        setState({ phase: 'ready', item: o.item });
        setOutcome({ outcome: o });
        return;
      }
      if (o.status === 400 && o.errorText === INSUFFICIENT_INVENTORY) {
        // Another client may have bought meanwhile: show what is on hand now, then the message.
        void api.getInventory(skuId).then((r) => {
          if (r.ok) {
            setState({ phase: 'ready', item: r.data });
            setOutcome({ outcome: o, extra: onHandLine(r.data.quantity ?? 0) });
          } else {
            setOutcome({ outcome: o });
          }
        });
        return;
      }
      setOutcome({ outcome: o });
    },
    [skuId],
  );

  const missing = state.phase === 'error' && state.status === 404;

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
                {(() => {
                  const badge = availability(state.item.quantity ?? 0);
                  return <span className={`badge ${badge.tone}`}>{badge.text}</span>;
                })()}
              </div>
            </>
          )}
        </div>
      </div>
      {state.phase !== 'loading' && (
        <>
          <StockOutcomeView outcome={outcome?.outcome ?? null} extra={outcome?.extra} />
          <div className="cards forms">
            <section className="card" aria-labelledby={`${id}-add`}>
              <h2 id={`${id}-add`}>
                <PlusCircle />
                Add stock
              </h2>
              {state.phase === 'error' && (
                <p className="note">Adding stock creates the SKU if it does not exist.</p>
              )}
              <StockForm operation="add" skuId={skuId} onOutcome={onOutcome} />
            </section>
            <section className="card" aria-labelledby={`${id}-buy`}>
              <h2 id={`${id}-buy`}>
                <ShoppingBag />
                Purchase
              </h2>
              <StockForm
                operation="purchase"
                skuId={skuId}
                onOutcome={onOutcome}
                unavailable={missing ? ADD_FIRST : null}
              />
            </section>
          </div>
        </>
      )}
    </section>
  );
}
