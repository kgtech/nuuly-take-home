import { useCallback, useEffect, useId, useRef, useState, type FormEvent, type ReactNode } from 'react';
import { api, type SkuItem } from '../api/client';
import { useIdempotentSubmit } from '../hooks/useIdempotentSubmit';
import { quantityReason, skuIdReason } from '../validation';
import { ErrorText, Hint, SubmitButton, Success, writeGuidance } from './Messages';

export type Operation = 'add' | 'purchase';

/** What a submit ended in; the page that owns the outcome area renders it (FE32). */
export type StockOutcome =
  | { kind: 'done'; operation: Operation; sent: number; item: SkuItem; key: string }
  | { kind: 'failed'; operation: Operation; sent: number; status: number; errorText: string };

interface Props {
  operation: Operation;
  skuId: string;
  onOutcome: (outcome: StockOutcome) => void;
  /** Id of an element that already shows the SKU ID reason (the Add page's field hint); else the form shows it. */
  skuReasonId?: string;
  /** A reason from the page that makes the submit unavailable, e.g. purchase on a missing SKU. */
  unavailable?: string | null;
}

const LABEL: Record<Operation, string> = { add: 'Add stock', purchase: 'Purchase' };

export function StockForm({ operation, skuId, onOutcome, skuReasonId, unavailable = null }: Props) {
  const id = useId();
  const [quantity, setQuantity] = useState('');

  const send = useCallback(
    (key: string) => {
      const body = { quantity: Number(quantity) };
      return operation === 'add' ? api.addStock(skuId, body, key) : api.purchase(skuId, body, key);
    },
    [operation, skuId, quantity],
  );
  const { submit, inFlight } = useIdempotentSubmit(send);

  const qtyReason = quantityReason(quantity);
  const skuReason = skuIdReason(skuId);
  const blocked = qtyReason !== null || skuReason !== null || unavailable !== null;
  const ownSkuReasonId = `${id}-sku`;
  const describedBy = [
    `${id}-hint`,
    ...(skuReason !== null ? [skuReasonId ?? ownSkuReasonId] : []),
    ...(unavailable !== null ? [`${id}-why`] : []),
  ];

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (blocked || inFlight) return;
    const sent = Number(quantity);
    const result = await submit(`${operation}\n${skuId}\n${sent}`);
    if (result?.phase === 'done') {
      setQuantity('');
      onOutcome({ kind: 'done', operation, sent, item: result.data, key: result.key });
    } else if (result?.phase === 'failed') {
      onOutcome({ kind: 'failed', operation, sent, status: result.status, errorText: result.errorText });
    }
  };

  return (
    <form
      onSubmit={(e) => void onSubmit(e)}
      className="stock-form"
      aria-label={LABEL[operation]}
      aria-busy={inFlight}
      noValidate
    >
      <div className="field">
        <label htmlFor={`${id}-qty`}>Quantity</label>
        <input
          id={`${id}-qty`}
          name="quantity"
          type="number"
          inputMode="numeric"
          min={1}
          step={1}
          value={quantity}
          onChange={(e) => setQuantity(e.target.value)}
          aria-invalid={(quantity !== '' && qtyReason !== null) || undefined}
          aria-describedby={`${id}-hint`}
          readOnly={inFlight}
        />
        <Hint id={`${id}-hint`}>{qtyReason}</Hint>
      </div>
      <SubmitButton
        label={LABEL[operation]}
        className="wide"
        unavailable={blocked}
        inFlight={inFlight}
        describedBy={describedBy}
      />
      {skuReason !== null && skuReasonId === undefined && <Hint id={ownSkuReasonId}>{skuReason}</Hint>}
      {unavailable !== null && <Hint id={`${id}-why`}>{unavailable}</Hint>}
    </form>
  );
}

/**
 * The outcome area: one success (role=status) or one failure (role=alert), focused when
 * it changes. `extra` replaces the generic guidance line (e.g. the on-hand count after
 * "Insufficient inventory").
 */
export function StockOutcomeView({ outcome, extra }: { outcome: StockOutcome | null; extra?: ReactNode }) {
  const statusRef = useRef<HTMLDivElement>(null);
  const alertRef = useRef<HTMLParagraphElement>(null);
  useEffect(() => {
    if (outcome !== null) (statusRef.current ?? alertRef.current)?.focus();
  }, [outcome]);
  if (outcome === null) return null;
  if (outcome.kind === 'done') {
    const { item, sent, operation, key } = outcome;
    return (
      <Success idempotencyKey={key} ref={statusRef}>
        {operation === 'add'
          ? `Added ${sent} to ${item.skuId}: now ${item.quantity}.`
          : `Purchased ${sent} of ${item.skuId}: ${item.quantity} left.`}
      </Success>
    );
  }
  return (
    <ErrorText text={outcome.errorText} ref={alertRef}>
      {extra ?? writeGuidance(outcome.status)}
    </ErrorText>
  );
}
