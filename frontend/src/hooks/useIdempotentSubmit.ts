import { useCallback, useRef, useState } from 'react';
import type { ApiResult } from '../api/client';

export type SubmitState<T> =
  | { phase: 'idle' }
  | { phase: 'inFlight' }
  | { phase: 'done'; data: T }
  | { phase: 'failed'; status: number; errorText: string };

/**
 * Idempotency-Key lifecycle: one UUID v4 per user action. The key is created on
 * the first submit, kept while the request is in flight or after a network
 * failure (so a retry replays the same action), and dropped only after the
 * server answered definitively. Submits while in flight are ignored (null).
 */
export function useIdempotentSubmit<T>(send: (key: string) => Promise<ApiResult<T>>) {
  const keyRef = useRef<string | null>(null);
  const busyRef = useRef(false);
  const [state, setState] = useState<SubmitState<T>>({ phase: 'idle' });

  const submit = useCallback(async (): Promise<SubmitState<T> | null> => {
    if (busyRef.current) return null;
    busyRef.current = true;
    keyRef.current ??= crypto.randomUUID();
    setState({ phase: 'inFlight' });
    const result = await send(keyRef.current);
    let next: SubmitState<T>;
    if (result.ok) {
      keyRef.current = null;
      next = { phase: 'done', data: result.data };
    } else {
      if (result.status !== 0) keyRef.current = null;
      next = { phase: 'failed', status: result.status, errorText: result.errorText };
    }
    busyRef.current = false;
    setState(next);
    return next;
  }, [send]);

  return { state, submit, inFlight: state.phase === 'inFlight' };
}
