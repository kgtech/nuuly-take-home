import type { MouseEvent, ReactNode, Ref } from 'react';
import { isRetryable } from '../hooks/useIdempotentSubmit';
import { CheckCircle, CircleNotch, WarningCircle } from './Icons';

export function Loading({ what }: { what: string }) {
  return (
    <p role="status" className="status">
      <CircleNotch />
      <span>Loading {what}…</span>
    </p>
  );
}

/** Plain guidance lines added under a verbatim server error, by status class (FE33). */
export const GUIDANCE = {
  retrySafe: 'Sending again is safe: the same request will not be applied twice.',
  refused: 'The service refused this request. Check the values and try again.',
} as const;

/** The guidance line for a failed write (POST or PUT), or null when the text alone says enough. */
export function writeGuidance(status: number): string | null {
  if (isRetryable(status)) return GUIDANCE.retrySafe;
  if (status === 400) return GUIDANCE.refused;
  return null;
}

/**
 * A server answer, verbatim, on the first line (PROMPT.md); `children` is the one
 * guidance line (text, a link or a button) that follows it.
 */
export function ErrorText({
  text,
  children,
  ref,
}: {
  text: string;
  children?: ReactNode;
  ref?: Ref<HTMLParagraphElement>;
}) {
  return (
    <p role="alert" className="error" tabIndex={-1} ref={ref}>
      <WarningCircle />
      <span className="lines">
        <span>{text}</span>
        {children && <span className="guidance">{children}</span>}
      </span>
    </p>
  );
}

/** A completed action: the sentence, then the Idempotency-Key it carried (FE9) behind a collapsed reference. */
export function Success({
  children,
  idempotencyKey,
  ref,
}: {
  children: ReactNode;
  idempotencyKey?: string;
  ref?: Ref<HTMLDivElement>;
}) {
  return (
    <div role="status" className="success" tabIndex={-1} ref={ref}>
      <CheckCircle />
      <div>
        <div>{children}</div>
        {idempotencyKey && (
          <details className="key">
            <summary>Request reference</summary>
            <span>Idempotency-Key {idempotencyKey}</span>
          </details>
        )}
      </div>
    </div>
  );
}

/** Helper text under a field; always rendered with its id so aria-describedby can always point at it. */
export function Hint({ id, children }: { id: string; children: ReactNode }) {
  return (
    <p id={id} className="hint" aria-live="polite">
      {children}
    </p>
  );
}

/**
 * The shared unavailable-button pattern (FE31): while a reason exists or a request is in
 * flight the button is aria-disabled (never disabled, so it keeps focus and its name),
 * aria-describedby links the visible reasons, and a click or Enter sends nothing.
 */
export function SubmitButton({
  label,
  unavailable,
  inFlight = false,
  describedBy = [],
  className,
  busyLabel = 'Sending…',
}: {
  label: string;
  /** True while a visible reason blocks the submit. */
  unavailable: boolean;
  inFlight?: boolean;
  /** Ids of the visible reasons (hint elements), kept even when they are empty. */
  describedBy?: string[];
  className?: string;
  busyLabel?: string;
}) {
  const blocked = unavailable || inFlight;
  const onClick = (e: MouseEvent<HTMLButtonElement>) => {
    if (blocked) e.preventDefault();
  };
  return (
    <button
      type="submit"
      className={className}
      aria-disabled={blocked || undefined}
      aria-describedby={describedBy.length > 0 ? describedBy.join(' ') : undefined}
      onClick={onClick}
    >
      {inFlight ? busyLabel : label}
    </button>
  );
}
