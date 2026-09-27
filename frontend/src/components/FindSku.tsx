import { useId, useState, type FormEvent } from 'react';
import { navigate, skuHref } from '../hooks/useHashRoute';
import { skuIdHint } from '../validation';

export function FindSku() {
  const id = useId();
  const [skuId, setSkuId] = useState('');
  const hint = skuIdHint(skuId);
  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (skuId !== '') navigate(skuHref(skuId));
  };
  return (
    <form onSubmit={onSubmit} className="find-form" aria-label="Find a SKU">
      <div className="field">
        <label htmlFor={`${id}-sku`}>SKU ID</label>
        <input
          id={`${id}-sku`}
          type="text"
          value={skuId}
          onChange={(e) => setSkuId(e.target.value)}
          autoComplete="off"
          aria-describedby={hint ? `${id}-hint` : undefined}
        />
        {hint && (
          <p id={`${id}-hint`} className="hint">
            {hint}
          </p>
        )}
      </div>
      <button type="submit">Open</button>
    </form>
  );
}
