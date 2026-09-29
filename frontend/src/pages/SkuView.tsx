import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { api, type SkuDetails, type SkuItem } from '../api/client';
import { StockForm, StockOutcomeView, type StockOutcome } from '../components/StockForm';
import { ErrorText, Loading } from '../components/Messages';
import { ArrowLeft, PencilSimple, PlusCircle, ShoppingBag } from '../components/Icons';
import { editHref } from '../hooks/useHashRoute';
import { formatCost } from '../cost';
import { INSUFFICIENT_INVENTORY, SKU_ID_PATTERN, SKU_NOT_FOUND } from '../validation';
import { availability } from './InventoryList';

type State =
  | { phase: 'loading' }
  | { phase: 'error'; status: number; errorText: string }
  | { phase: 'ready'; item: SkuItem };

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
  // Sequence of outcomes: the re-fetch after "Insufficient inventory" is ignored when a newer outcome
  // (e.g. an add) has landed meanwhile, so a late GET never overwrites a fresher quantity (F-08).
  const seq = useRef(0);
  useEffect(() => {
    let live = true;
    if (invalid) return;
    void api.getSku(skuId).then((r) => {
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
      const mine = ++seq.current;
      if (o.kind === 'done') {
        // The write response is the whole SkuItem, details included (FE38): it replaces what the page holds.
        setOutcome({ outcome: o });
        setState({ phase: 'ready', item: o.item });
        return;
      }
      if (o.status === 400 && o.errorText === INSUFFICIENT_INVENTORY) {
        // Another client may have bought meanwhile: show what is on hand now, then the message.
        void api.getSku(skuId).then((r) => {
          if (seq.current !== mine) return;
          if (r.ok) {
            setState({ phase: 'ready', item: r.data });
            setOutcome({ outcome: o, extra: onHandLine(r.data.quantity) });
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
                  {state.item.quantity.toLocaleString()}
                </span>
                {(() => {
                  const badge = availability(state.item.quantity);
                  return <span className={`badge ${badge.tone}`}>{badge.text}</span>;
                })()}
              </div>
            </>
          )}
        </div>
      </div>
      {state.phase === 'ready' && <Details skuId={skuId} details={state.item.details} />}
      {state.phase !== 'loading' && (
        <>
          <StockOutcomeView outcome={outcome?.outcome ?? null} extra={outcome?.extra} />
          <div className="cards forms">
            <section className="card" aria-labelledby={`${id}-add`}>
              <h2 id={`${id}-add`}>
                <PlusCircle />
                Add stock
              </h2>
              {missing && <p className="note">Adding stock creates the SKU if it does not exist.</p>}
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

/** The v2 details card: name, description, cost from minor units, lazy thumbnails (FE36, FE38). */
function Details({ skuId, details }: { skuId: string; details: SkuDetails | undefined }) {
  const id = useId();
  if (details === undefined) {
    return (
      <section className="card details" aria-labelledby={`${id}-h`}>
        <h2 id={`${id}-h`}>No details yet</h2>
        <p className="note">Give this SKU a name, a description, a cost and images.</p>
        <p style={{ margin: 0 }}>
          <a href={editHref(skuId)} className="back">
            <PencilSimple />
            Add details
          </a>
        </p>
      </section>
    );
  }
  const images = details.images ?? [];
  return (
    <section className="card details" aria-labelledby={`${id}-h`}>
      <h2 id={`${id}-h`}>{details.name}</h2>
      {details.description && <p className="description">{details.description}</p>}
      {details.cost && (
        <p className="cost">
          <span className="label">Cost</span>{' '}
          <span data-testid="cost">{formatCost(details.cost)}</span>
        </p>
      )}
      {images.length > 0 && (
        <ul className="images" aria-label="Images">
          {images.map((url, i) => (
            <li key={`${i}-${url}`}>
              <Thumbnail url={url} alt={details.name} />
            </li>
          ))}
        </ul>
      )}
      <p style={{ margin: 0 }}>
        <a href={editHref(skuId)} className="back">
          <PencilSimple />
          Edit details
        </a>
      </p>
    </section>
  );
}

function Thumbnail({ url, alt }: { url: string; alt: string }) {
  const [broken, setBroken] = useState(false);
  if (broken) {
    return (
      <span className="thumb fallback" role="img" aria-label={`${alt} (image unavailable)`}>
        <span aria-hidden="true">Image unavailable</span>
      </span>
    );
  }
  return <img className="thumb" src={url} alt={alt} loading="lazy" onError={() => setBroken(true)} />;
}
