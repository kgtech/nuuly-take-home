import { useCallback, useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { api } from '../api/client';
import { DetailsFields, useDetailsForm } from '../components/DetailsFields';
import { SKU_PLACEHOLDER } from '../components/FindSku';
import { ArrowLeft } from '../components/Icons';
import { ErrorText, Hint, SubmitButton, writeGuidance } from '../components/Messages';
import { editHref, navigate, skuHref } from '../hooks/useHashRoute';
import { useIdempotentSubmit } from '../hooks/useIdempotentSubmit';
import { initialStockReason, NOT_A_NUMBER, skuIdReason } from '../validation';

type Failure = { status: number; errorText: string };

/** Line under a 412 on the create PUT: the server text says details changed, which the user never read (FE33). */
const EXISTS = 'A SKU with this ID already exists. Open it to edit its details or add stock.';

/**
 * #/new: two steps (FE34). A create-only PUT .../details, then, for initial stock above 0, a keyed add.
 * The SKU exists at 0 stock between them, so a failed add locks the form and offers Retry for the add alone.
 */
export function CreateSkuPage() {
  const id = useId();
  const [skuId, setSkuId] = useState('');
  const [initial, setInitial] = useState('');
  const [initialBadInput, setInitialBadInput] = useState(false);
  const details = useDetailsForm();
  const alertRef = useRef<HTMLParagraphElement>(null);

  const skuReason = skuIdReason(skuId);
  const initialReason = initialBadInput ? NOT_A_NUMBER : initialStockReason(initial);
  const quantity = initial === '' ? 0 : Number(initial);

  const [created, setCreated] = useState(false);
  const [putInFlight, setPutInFlight] = useState(false);
  const [putFailure, setPutFailure] = useState<Failure | null>(null);
  const putBusy = useRef(false);

  const send = useCallback((key: string) => api.addStock(skuId, { quantity }, key), [skuId, quantity]);
  const add = useIdempotentSubmit(send);
  const inFlight = putInFlight || add.inFlight;
  const failure: Failure | null = putFailure ?? (add.state.phase === 'failed' ? add.state : null);

  useEffect(() => {
    if (failure !== null) alertRef.current?.focus();
  }, [failure]);

  const blockingIds = [
    ...(skuReason !== null ? [`${id}-sku-hint`] : []),
    ...details.blockingHintIds,
    ...(initialReason !== null ? [`${id}-initial-hint`] : []),
  ];
  const blocked = blockingIds.length > 0;

  const runAdd = async () => {
    const result = await add.submit(`add\n${skuId}\n${quantity}`);
    if (result?.phase === 'done') navigate(skuHref(skuId));
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (blocked || inFlight || putBusy.current) return;
    putBusy.current = true;
    setPutFailure(null);
    setPutInFlight(true);
    let ok: boolean;
    try {
      const r = await api.putDetails(skuId, details.body(), { ifNoneMatch: '*' });
      ok = r.ok;
      if (!r.ok) setPutFailure({ status: r.status, errorText: r.errorText });
    } finally {
      putBusy.current = false;
      setPutInFlight(false);
    }
    if (!ok) return;
    setCreated(true);
    if (quantity === 0) navigate(skuHref(skuId));
    else await runAdd();
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
            readOnly={inFlight || created}
          />
          <Hint id={`${id}-sku-hint`}>{skuReason}</Hint>
        </div>
        <DetailsFields form={details} readOnly={inFlight || created} />
        <div className="field">
          <label htmlFor={`${id}-initial`}>Initial stock (optional, default 0)</label>
          <input
            id={`${id}-initial`}
            type="number"
            inputMode="numeric"
            min={0}
            step={1}
            value={initial}
            onChange={(e) => setInitial(e.target.value)}
            onInput={(e) => setInitialBadInput(e.currentTarget.validity?.badInput ?? false)}
            aria-invalid={initialReason !== null || undefined}
            aria-describedby={`${id}-initial-hint`}
            readOnly={inFlight || created}
          />
          <Hint id={`${id}-initial-hint`}>{initialReason}</Hint>
        </div>
        {created ? (
          <button type="button" className="wide" aria-disabled={inFlight || undefined} onClick={() => !inFlight && void runAdd()}>
            {inFlight ? 'Sending…' : 'Retry'}
          </button>
        ) : (
          <SubmitButton label="Create SKU" className="wide" unavailable={blocked} inFlight={inFlight} describedBy={blockingIds} />
        )}
        {failure !== null && (
          <ErrorText text={failure.errorText} ref={alertRef}>
            {failure.status === 412 && !created ? (
              <>
                {EXISTS} <a href={editHref(skuId)}>Open {skuId}</a>
              </>
            ) : created ? (
              <>
                The SKU exists at 0 stock until the add succeeds. {writeGuidance(failure.status)}{' '}
                <a href={skuHref(skuId)}>Open {skuId}</a>
              </>
            ) : (
              writeGuidance(failure.status)
            )}
          </ErrorText>
        )}
      </form>
    </section>
  );
}
