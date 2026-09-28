import { useEffect, useRef, useState, type FormEvent } from 'react';
import { api, type SkuItem } from '../api/client';
import { DetailsFields, useDetailsForm, valuesFrom } from '../components/DetailsFields';
import { ArrowLeft } from '../components/Icons';
import { ErrorText, Hint, Loading, SubmitButton, writeGuidance } from '../components/Messages';
import { navigate, NEW_HREF, skuHref } from '../hooks/useHashRoute';
import { SKU_ID_PATTERN, SKU_NOT_FOUND } from '../validation';

type Loaded =
  | { phase: 'loading' }
  | { phase: 'error'; status: number; errorText: string }
  | { phase: 'ready'; item: SkuItem; etag: string | null };

type Saved = { status: number; errorText: string } | null;

/** Shown when the read carried no ETag (a proxy stripped it): FE37 never sends an unconditional PUT (F-fe-04). */
export const NO_VERSION = 'The service did not return a version; reload and try again.';

/** #/sku/:id/edit: GET /v2 then PUT with If-Match from the ETag (FE37). Rendered with key={skuId}. */
export function EditSkuPage({ skuId }: { skuId: string }) {
  const invalid = !SKU_ID_PATTERN.test(skuId);
  const [loaded, setLoaded] = useState<Loaded>(
    invalid ? { phase: 'error', status: 404, errorText: SKU_NOT_FOUND } : { phase: 'loading' },
  );
  const [attempt, setAttempt] = useState(0);
  const [failure, setFailure] = useState<Saved>(null);
  const [inFlight, setInFlight] = useState(false);
  const busy = useRef(false);
  const alertRef = useRef<HTMLParagraphElement>(null);
  const details = useDetailsForm();
  const { reset, nameRef } = details;

  useEffect(() => {
    if (invalid) return;
    let live = true;
    void api.getSku(skuId).then((r) => {
      if (!live) return;
      if (r.ok) {
        reset(valuesFrom(r.data.details));
        setLoaded({ phase: 'ready', item: r.data, etag: r.etag });
      } else {
        setLoaded({ phase: 'error', status: r.status, errorText: r.errorText });
      }
    });
    return () => {
      live = false;
    };
  }, [skuId, attempt, invalid, reset]);

  useEffect(() => {
    if (failure !== null || loaded.phase === 'error') alertRef.current?.focus();
  }, [failure, loaded]);

  // After Reload (F-07) the button that had focus is gone; land on the Name field once the form is back.
  useEffect(() => {
    if (attempt > 0 && loaded.phase === 'ready') nameRef.current?.focus();
  }, [attempt, loaded, nameRef]);

  const reload = () => {
    setFailure(null);
    setLoaded({ phase: 'loading' });
    setAttempt((n) => n + 1);
  };

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (loaded.phase !== 'ready' || loaded.etag === null || !details.valid || busy.current) return;
    busy.current = true;
    setInFlight(true);
    try {
      const r = await api.replaceSkuDetails(skuId, details.body(), loaded.etag);
      if (r.ok) navigate(skuHref(skuId));
      else setFailure({ status: r.status, errorText: r.errorText });
    } finally {
      busy.current = false;
      setInFlight(false);
    }
  };

  const createLink = (
    <>
      <a href={NEW_HREF}>Create it as a new SKU</a> with its details and stock.
    </>
  );
  const hasDetails = loaded.phase === 'ready' && loaded.item.details !== undefined;

  return (
    <section className="page">
      <p style={{ margin: 0 }}>
        <a href={skuHref(skuId)} className="back">
          <ArrowLeft />
          Back to SKU
        </a>
      </p>
      <div className="page-head">
        <p className="kicker">{skuId}</p>
        <h1>{loaded.phase === 'ready' && !hasDetails ? 'Add details' : 'Edit details'}</h1>
        <p className="subtitle">Saves the whole set of details at once; stock is not changed here.</p>
      </div>
      {loaded.phase === 'loading' && <Loading what="SKU" />}
      {loaded.phase === 'error' && (
        <div className="stack">
          <ErrorText text={loaded.errorText} ref={alertRef}>
            {loaded.status === 404 ? createLink : null}
          </ErrorText>
          {loaded.status !== 404 && (
            <button type="button" className="secondary" onClick={reload}>
              Retry
            </button>
          )}
        </div>
      )}
      {loaded.phase === 'ready' && (
        <form className="card" aria-label="Edit details" aria-busy={inFlight} onSubmit={(e) => void onSubmit(e)} noValidate>
          <DetailsFields form={details} readOnly={inFlight} />
          <SubmitButton
            label="Save details"
            className="wide"
            unavailable={!details.valid || loaded.etag === null}
            inFlight={inFlight}
            describedBy={[...details.blockingHintIds, ...(loaded.etag === null ? [`${details.id}-version`] : [])]}
          />
          {loaded.etag === null && <Hint id={`${details.id}-version`}>{NO_VERSION}</Hint>}
          {failure !== null && (
            <ErrorText text={failure.errorText} ref={alertRef}>
              {failure.status === 412 ? (
                <>
                  <button type="button" className="secondary" onClick={reload}>
                    Reload
                  </button>{' '}
                  to see the current details, then make your change again.
                </>
              ) : failure.status === 404 ? (
                createLink
              ) : (
                writeGuidance(failure.status)
              )}
            </ErrorText>
          )}
        </form>
      )}
    </section>
  );
}
