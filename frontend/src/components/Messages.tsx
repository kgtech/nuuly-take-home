import type { Ref } from 'react';

export function Loading({ what }: { what: string }) {
  return (
    <p role="status" className="muted">
      Loading {what}…
    </p>
  );
}

export function ErrorText({ text, ref }: { text: string; ref?: Ref<HTMLParagraphElement> }) {
  return (
    <p role="alert" className="error" tabIndex={-1} ref={ref}>
      {text}
    </p>
  );
}
