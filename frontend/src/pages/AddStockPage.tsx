import { useCallback, useId, useState } from 'react';
import type { InventoryItem } from '../api/client';
import { StockForm } from '../components/StockForm';
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
    <section>
      <p>
        <a href="#/">← Inventory</a>
      </p>
      <h1>Add stock</h1>
      <p className="muted">Creates the SKU if it does not exist.</p>
      <div className="field">
        <label htmlFor={`${id}-sku`}>SKU ID</label>
        <input
          id={`${id}-sku`}
          type="text"
          value={skuId}
          onChange={(e) => setSkuId(e.target.value)}
          autoComplete="off"
          aria-invalid={hint !== null || undefined}
          aria-describedby={hint ? `${id}-hint` : undefined}
        />
        <p id={`${id}-hint`} className="hint" aria-live="polite">
          {hint}
        </p>
      </div>
      <StockForm operation="add" skuId={skuId} onSuccess={onSuccess} />
      {result && (
        <p>
          <a href={skuHref(result.skuId ?? skuId)}>View {result.skuId}</a>
        </p>
      )}
    </section>
  );
}
