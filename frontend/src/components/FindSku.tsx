import { useId, useState, type FormEvent } from 'react';
import { navigate, skuHref } from '../hooks/useHashRoute';
import { SKU_ID_EMPTY_TO_OPEN, skuIdReason } from '../validation';
import { Hint, SubmitButton } from './Messages';

export const SKU_PLACEHOLDER = 'e.g. DRS-0142-S';

export function FindSku() {
  const id = useId();
  const [skuId, setSkuId] = useState('');
  const reason = skuIdReason(skuId, SKU_ID_EMPTY_TO_OPEN);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    if (reason !== null) return;
    navigate(skuHref(skuId));
  };
  return (
    <section className="card" aria-labelledby={`${id}-h`}>
      <h2 id={`${id}-h`}>Find a SKU</h2>
      <form onSubmit={onSubmit} className="find-form" aria-label="Find a SKU" noValidate>
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
        <SubmitButton label="Open" unavailable={reason !== null} describedBy={[`${id}-hint`]} />
      </form>
    </section>
  );
}
