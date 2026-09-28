import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { navigate, skuHref } from '../hooks/useHashRoute';
import { SKU_ID_PATTERN, SKU_NOT_FOUND, skuIdHint } from '../validation';
import { ErrorText } from './Messages';

export const SKU_PLACEHOLDER = 'e.g. DRS-0142-S';

export function FindSku() {
  const id = useId();
  const [skuId, setSkuId] = useState('');
  const [rejected, setRejected] = useState<string | null>(null);
  const alertRef = useRef<HTMLParagraphElement>(null);
  const hint = skuIdHint(skuId);

  useEffect(() => {
    if (rejected !== null) alertRef.current?.focus();
  }, [rejected]);

  const onSubmit = (e: FormEvent) => {
    e.preventDefault();
    // GET of an id the server is certain to reject: answer with its text, no request.
    if (!SKU_ID_PATTERN.test(skuId)) {
      setRejected(SKU_NOT_FOUND);
      return;
    }
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
            onChange={(e) => {
              setSkuId(e.target.value);
              setRejected(null);
            }}
            autoComplete="off"
            placeholder={SKU_PLACEHOLDER}
            aria-invalid={hint !== null || undefined}
            aria-describedby={hint ? `${id}-hint` : undefined}
          />
          <p id={`${id}-hint`} className="hint" aria-live="polite">
            {hint}
          </p>
        </div>
        <button type="submit">Open</button>
        {rejected !== null && <ErrorText text={rejected} ref={alertRef} />}
      </form>
    </section>
  );
}
