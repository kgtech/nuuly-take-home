import { useCallback, useId, useState } from 'react';
import type { InventoryItem } from '../api/client';
import { StockForm } from '../components/StockForm';
import { SKU_PLACEHOLDER } from '../components/FindSku';
import { ArrowLeft, ArrowRight } from '../components/Icons';
import { skuHref } from '../hooks/useHashRoute';
import { skuIdHint } from '../validation';

/** Add stock to any SKU by id, creating it when it does not exist. */
export function AddStockPage() {
  const id = useId();
  const [skuId, setSkuId] = useState('');
  const [result, setResult] = useState<InventoryItem | null>(null);
  const onSuccess = useCallback((item: InventoryItem) => setResult(item), []);
  const hint = skuIdHint(skuId);

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
            aria-invalid={hint !== null || undefined}
            aria-describedby={hint ? `${id}-hint` : undefined}
          />
          <p id={`${id}-hint`} className="hint" aria-live="polite">
            {hint}
          </p>
        </div>
        <StockForm operation="add" skuId={skuId} onSuccess={onSuccess} />
        {result && (
          <p style={{ margin: 0 }}>
            <a href={skuHref(result.skuId ?? skuId)} className="back">
              View {result.skuId}
              <ArrowRight />
            </a>
          </p>
        )}
      </div>
    </section>
  );
}
