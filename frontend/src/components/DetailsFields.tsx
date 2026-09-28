import { useId, useRef, useState } from 'react';
import type { SkuDetails } from '../api/client';
import {
  costAmountReason,
  costCurrencyReason,
  descriptionReason,
  IMAGES_MAX,
  imagesReason,
  nameReason,
  parseImages,
} from '../validation';
import { Hint } from './Messages';

export interface DetailsValues {
  name: string;
  description: string;
  amount: string;
  currency: string;
  images: string;
}

export function valuesFrom(details: SkuDetails | undefined): DetailsValues {
  return {
    name: details?.name ?? '',
    description: details?.description ?? '',
    amount: details?.cost ? String(details.cost.amount) : '',
    currency: details?.cost?.currency ?? '',
    images: (details?.images ?? []).join('\n'),
  };
}

export type DetailsField = keyof DetailsValues;

/** Field state, per-field reasons and the request body for the SkuDetails schema (shared by create and edit, FE35). */
export function useDetailsForm(initial: DetailsValues = valuesFrom(undefined)) {
  const id = useId();
  const nameRef = useRef<HTMLInputElement>(null);
  const [values, setValues] = useState<DetailsValues>(initial);
  const reasons: Record<DetailsField, string | null> = {
    name: nameReason(values.name),
    description: descriptionReason(values.description),
    amount: costAmountReason(values.amount, values.currency),
    currency: costCurrencyReason(values.amount, values.currency),
    images: imagesReason(values.images),
  };
  const hintId = (f: DetailsField) => `${id}-${f}-hint`;
  const blockingHintIds = (Object.keys(reasons) as DetailsField[]).filter((f) => reasons[f] !== null).map(hintId);
  const set = (f: DetailsField, v: string) => setValues((cur) => ({ ...cur, [f]: v }));
  const body = (): SkuDetails => {
    const d: SkuDetails = { name: values.name, description: values.description, images: parseImages(values.images) };
    if (values.amount !== '' && values.currency !== '') d.cost = { amount: Number(values.amount), currency: values.currency };
    return d;
  };
  return { id, nameRef, values, set, reset: setValues, reasons, hintId, blockingHintIds, valid: blockingHintIds.length === 0, body };
}

export type DetailsForm = ReturnType<typeof useDetailsForm>;

/** The five details fields with their labels and always-present hints. */
export function DetailsFields({ form, readOnly }: { form: DetailsForm; readOnly: boolean }) {
  const { id, nameRef, values, set, reasons, hintId } = form;
  const invalid = (f: DetailsField) => (values[f] !== '' && reasons[f] !== null) || undefined;
  // No maxLength on the text fields: it would truncate a paste silently, and the reason already blocks (F-09).
  return (
    <>
      <div className="field">
        <label htmlFor={`${id}-name`}>Name</label>
        <input
          id={`${id}-name`}
          ref={nameRef}
          type="text"
          value={values.name}
          onChange={(e) => set('name', e.target.value)}
          autoComplete="off"
          aria-invalid={invalid('name')}
          aria-describedby={hintId('name')}
          readOnly={readOnly}
        />
        <Hint id={hintId('name')}>{reasons.name}</Hint>
      </div>
      <div className="field">
        <label htmlFor={`${id}-description`}>Description (optional)</label>
        <textarea
          id={`${id}-description`}
          value={values.description}
          onChange={(e) => set('description', e.target.value)}
          rows={4}
          aria-invalid={invalid('description')}
          aria-describedby={hintId('description')}
          readOnly={readOnly}
        />
        <Hint id={hintId('description')}>{reasons.description}</Hint>
      </div>
      <div className="cost-row">
        <div className="field">
          <label htmlFor={`${id}-amount`}>Cost amount (minor units, e.g. cents)</label>
          <input
            id={`${id}-amount`}
            type="number"
            inputMode="numeric"
            min={0}
            step={1}
            value={values.amount}
            onChange={(e) => set('amount', e.target.value)}
            aria-invalid={invalid('amount')}
            aria-describedby={hintId('amount')}
            readOnly={readOnly}
          />
          <Hint id={hintId('amount')}>{reasons.amount}</Hint>
        </div>
        <div className="field">
          <label htmlFor={`${id}-currency`}>Currency (e.g. USD)</label>
          <input
            id={`${id}-currency`}
            type="text"
            value={values.currency}
            onChange={(e) => set('currency', e.target.value)}
            maxLength={3}
            autoComplete="off"
            autoCapitalize="characters"
            aria-invalid={invalid('currency')}
            aria-describedby={hintId('currency')}
            readOnly={readOnly}
          />
          <Hint id={hintId('currency')}>{reasons.currency}</Hint>
        </div>
      </div>
      <div className="field">
        <label htmlFor={`${id}-images`}>Image URLs (one per line, up to {IMAGES_MAX})</label>
        <textarea
          id={`${id}-images`}
          value={values.images}
          onChange={(e) => set('images', e.target.value)}
          rows={3}
          spellCheck={false}
          aria-invalid={invalid('images')}
          aria-describedby={hintId('images')}
          readOnly={readOnly}
        />
        <Hint id={hintId('images')}>{reasons.images}</Hint>
      </div>
    </>
  );
}
