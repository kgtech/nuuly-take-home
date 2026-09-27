import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { api, type InventoryItem } from '../api/client';
import { useIdempotentSubmit } from '../hooks/useIdempotentSubmit';
import { quantityHint } from '../validation';
import { ErrorText } from './Messages';

export type Operation = 'add' | 'purchase';

interface Props {
  operation: Operation;
  skuId: string;
  onSuccess: (item: InventoryItem) => void;
}

const LABEL: Record<Operation, string> = { add: 'Add stock', purchase: 'Purchase' };

export function StockForm({ operation, skuId, onSuccess }: Props) {
  const id = useId();
  const [quantity, setQuantity] = useState('');
  const [sent, setSent] = useState(0);
  const statusRef = useRef<HTMLParagraphElement>(null);
  const alertRef = useRef<HTMLParagraphElement>(null);

  const send = useCallback(
    (key: string) => {
      const body = { quantity: Number(quantity) };
      return operation === 'add' ? api.addStock(skuId, body, key) : api.purchase(skuId, body, key);
    },
    [operation, skuId, quantity],
  );
  const { state, submit, inFlight } = useIdempotentSubmit(send);

  // Move focus to the outcome so keyboard and screen-reader users hear it.
  useEffect(() => {
    if (state.phase === 'done') statusRef.current?.focus();
    else if (state.phase === 'failed') alertRef.current?.focus();
  }, [state]);

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setSent(Number(quantity));
    const result = await submit();
    if (result?.phase === 'done') {
      onSuccess(result.data);
      setQuantity('');
    }
  };

  const hint = quantityHint(quantity);
  const done = state.phase === 'done' ? state.data : null;

  return (
    <form onSubmit={(e) => void onSubmit(e)} className="stock-form" aria-busy={inFlight} noValidate>
      <div className="field">
        <label htmlFor={`${id}-qty`}>Quantity</label>
        <input
          id={`${id}-qty`}
          name="quantity"
          type="number"
          inputMode="numeric"
          min={1}
          step={1}
          required
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
          aria-describedby={hint ? `${id}-hint` : undefined}
          disabled={inFlight}
        />
        {hint && (
          <p id={`${id}-hint`} className="hint">
            {hint}
          </p>
        )}
      </div>
      <button type="submit" disabled={inFlight}>
        {inFlight ? 'Sending…' : LABEL[operation]}
      </button>
      {done && (
        <p role="status" className="success" tabIndex={-1} ref={statusRef}>
          {operation === 'add'
            ? `Added ${sent} to ${done.skuId}: now ${done.quantity}.`
            : `Purchased ${sent} of ${done.skuId}: ${done.quantity} left.`}
        </p>
      )}
      {state.phase === 'failed' && <ErrorText text={state.errorText} ref={alertRef} />}
    </form>
  );
}
