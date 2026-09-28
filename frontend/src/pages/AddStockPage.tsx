import { useId, useState } from 'react';
import { StockForm, StockOutcomeView, type StockOutcome } from '../components/StockForm';
import { SKU_PLACEHOLDER } from '../components/FindSku';
import { ArrowLeft, ArrowRight } from '../components/Icons';
import { Hint } from '../components/Messages';
import { skuHref } from '../hooks/useHashRoute';
import { skuIdReason } from '../validation';

/** Add stock to any SKU by id, creating it when it does not exist. */
export function AddStockPage() {
  const id = useId();
  const [skuId, setSkuId] = useState('');
  const [outcome, setOutcome] = useState<StockOutcome | null>(null);
  const reason = skuIdReason(skuId);
  const created = outcome?.kind === 'done' ? outcome.item : null;

  return (
    <section className="page">
      <p style={{ margin: 0 }}>
        <a href="#/" className="back">
          <ArrowLeft />
          Back to inventory
        </a>
      </p>
      <div className="page-head">
        <p className="kicker">New arrivals</p>
        <h1>Add stock</h1>
        <p className="subtitle">Creates the SKU if it does not exist.</p>
      </div>
      <div className="card">
        <div className="field">
          <label htmlFor={`${id}-sku`}>SKU ID</label>
          <input
            id={`${id}-sku`}
            type="text"
            value={skuId}
            onChange={(e) => setSkuId(e.target.value)}
            autoComplete="off"
            placeholder={SKU_PLACEHOLDER}
            aria-invalid={(skuId !== '' && reason !== null) || undefined}
            aria-describedby={`${id}-hint`}
          />
          <Hint id={`${id}-hint`}>{reason}</Hint>
        </div>
        <StockForm operation="add" skuId={skuId} onOutcome={setOutcome} skuReasonId={`${id}-hint`} />
        <StockOutcomeView outcome={outcome} />
        {created && (
          <p style={{ margin: 0 }}>
            <a href={skuHref(created.skuId ?? skuId)} className="back">
              View {created.skuId}
              <ArrowRight />
            </a>
          </p>
        )}
      </div>
    </section>
  );
}
