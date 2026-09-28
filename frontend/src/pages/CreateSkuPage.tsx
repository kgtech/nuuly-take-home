import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { api, type CreateSkuRequest } from '../api/client';
import { DetailsFields, useDetailsForm } from '../components/DetailsFields';
import { SKU_PLACEHOLDER } from '../components/FindSku';
import { ArrowLeft } from '../components/Icons';
import { ErrorText, Hint, SubmitButton, writeGuidance } from '../components/Messages';
import { navigate, skuHref } from '../hooks/useHashRoute';
import { useIdempotentSubmit } from '../hooks/useIdempotentSubmit';
import { initialStockReason, NOT_A_NUMBER, skuIdReason } from '../validation';

/** #/new: one POST /v2/inventory/{skuId} with details and optional initial stock (FE34). */
export function CreateSkuPage() {
  const id = useId();
  const [skuId, setSkuId] = useState('');
  const [initial, setInitial] = useState('');
  const [initialBadInput, setInitialBadInput] = useState(false);
  const details = useDetailsForm();
  const alertRef = useRef<HTMLParagraphElement>(null);

  const skuReason = skuIdReason(skuId);
  const initialReason = initialBadInput ? NOT_A_NUMBER : initialStockReason(initial);
  const request = (): CreateSkuRequest => ({ details: details.body(), initialQuantity: initial === '' ? 0 : Number(initial) });

  const send = useCallback((key: string) => api.createSku(skuId, request(), key), [skuId, initial, details.values]); // eslint-disable-line react-hooks/exhaustive-deps
  const { state, submit, inFlight } = useIdempotentSubmit(send);

  useEffect(() => {
    if (state.phase === 'failed') alertRef.current?.focus();
  }, [state]);

  const blockingIds = [
    ...(skuReason !== null ? [`${id}-sku-hint`] : []),
    ...details.blockingHintIds,
    ...(initialReason !== null ? [`${id}-initial-hint`] : []),
  ];
  const blocked = blockingIds.length > 0;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (blocked || inFlight) return;
    const result = await submit(JSON.stringify([skuId, request()]));
    if (result?.phase === 'done') navigate(skuHref(skuId));
  };

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
        <h1>New SKU</h1>
        <p className="subtitle">Creates the SKU with its details and, optionally, its first stock in one step.</p>
      </div>
      <form className="card" aria-label="New SKU" aria-busy={inFlight} onSubmit={(e) => void onSubmit(e)} noValidate>
        <div className="field">
          <label htmlFor={`${id}-sku`}>SKU ID</label>
          <input
            id={`${id}-sku`}
            type="text"
            value={skuId}
            onChange={(e) => setSkuId(e.target.value)}
            autoComplete="off"
            placeholder={SKU_PLACEHOLDER}
            aria-invalid={(skuId !== '' && skuReason !== null) || undefined}
            aria-describedby={`${id}-sku-hint`}
            readOnly={inFlight}
          />
          <Hint id={`${id}-sku-hint`}>{skuReason}</Hint>
        </div>
        <DetailsFields form={details} readOnly={inFlight} />
        <div className="field">
          <label htmlFor={`${id}-initial`}>Initial stock (optional, default 0)</label>
          <input
            id={`${id}-initial`}
            type="number"
            inputMode="numeric"
            min={0}
            step={1}
            value={initial}
            onChange={(e) => {
              setInitial(e.target.value);
              setInitialBadInput(e.target.validity?.badInput ?? false);
            }}
            aria-invalid={initialReason !== null || undefined}
            aria-describedby={`${id}-initial-hint`}
            readOnly={inFlight}
          />
          <Hint id={`${id}-initial-hint`}>{initialReason}</Hint>
        </div>
        <SubmitButton label="Create SKU" className="wide" unavailable={blocked} inFlight={inFlight} describedBy={blockingIds} />
        {state.phase === 'failed' && (
          <ErrorText text={state.errorText} ref={alertRef}>
            {state.status === 409 ? (
              <>
                This SKU already exists. <a href={skuHref(skuId)}>Open {skuId}</a> to edit its details or add stock.
              </>
            ) : (
              writeGuidance(state.status)
            )}
          </ErrorText>
        )}
      </form>
    </section>
  );
}
