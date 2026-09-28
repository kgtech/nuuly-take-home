import type { ReactNode, Ref } from 'react';
import { CheckCircle, CircleNotch, WarningCircle } from './Icons';

export function Loading({ what }: { what: string }) {
  return (
    <p role="status" className="status">
      <CircleNotch />
      <span>Loading {what}…</span>
    </p>
  );
}

export function ErrorText({ text, ref }: { text: string; ref?: Ref<HTMLParagraphElement> }) {
  return (
    <p role="alert" className="error" tabIndex={-1} ref={ref}>
      <WarningCircle />
      <span>{text}</span>
    </p>
  );
}

/** A completed action: the sentence, then the Idempotency-Key the request carried (FE9). */
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
        {idempotencyKey && <span className="key">Idempotency-Key {idempotencyKey}</span>}
      </div>
    </div>
  );
}
