import { useCallback, useRef, useState } from 'react';
import type { ApiResult } from '../api/client';

export type SubmitState<T> =
  | { phase: 'idle' }
  | { phase: 'inFlight' }
  | { phase: 'done'; data: T; key: string }
  | { phase: 'failed'; status: number; errorText: string };

/** True when the server may not have applied the request: retry with the same key. */
export function isRetryable(status: number): boolean {
  return status === 0 || status === 408 || status === 429 || status >= 500;
}

/** UUID v4; crypto.randomUUID needs a secure context, so fall back to getRandomValues. */
export function uuidV4(): string {
  if (typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  const b = crypto.getRandomValues(new Uint8Array(16));
  b[6] = ((b[6] ?? 0) & 0x0f) | 0x40;
  b[8] = ((b[8] ?? 0) & 0x3f) | 0x80;
  const hex = Array.from(b, (x) => x.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * Idempotency-Key lifecycle: one UUID v4 per user action. The key is created on
 * the first submit and kept while the request is in flight and after any
 * outcome where the server may still have applied it (network failure,
 * timeout, 408, 429, 5xx), so a retry replays the same action. It is dropped
 * after a definitive answer (2xx or another 4xx) and when the action's inputs
 * (the fingerprint) change, since a different request must not reuse the key
 * (S8). Submits while in flight are ignored (null).
 */
export function useIdempotentSubmit<T>(send: (key: string) => Promise<ApiResult<T>>) {
  const keyRef = useRef<{ key: string; fingerprint: string } | null>(null);
  const busyRef = useRef(false);
  const [state, setState] = useState<SubmitState<T>>({ phase: 'idle' });

  const submit = useCallback(
    async (fingerprint: string): Promise<SubmitState<T> | null> => {
      if (busyRef.current) return null;
      busyRef.current = true;
      try {
        if (keyRef.current === null || keyRef.current.fingerprint !== fingerprint) {
          keyRef.current = { key: uuidV4(), fingerprint };
        }
        setState({ phase: 'inFlight' });
        const key = keyRef.current.key;
        const result = await send(key);
        let next: SubmitState<T>;
        if (result.ok) {
          keyRef.current = null;
          next = { phase: 'done', data: result.data, key };
        } else {
          if (!isRetryable(result.status)) keyRef.current = null;
          next = { phase: 'failed', status: result.status, errorText: result.errorText };
        }
        setState(next);
        return next;
      } finally {
        busyRef.current = false;
      }
    },
    [send],
  );

  return { state, submit, inFlight: state.phase === 'inFlight' };
}
