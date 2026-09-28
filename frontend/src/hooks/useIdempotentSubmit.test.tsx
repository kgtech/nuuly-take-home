import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useIdempotentSubmit } from './useIdempotentSubmit';
import type { ApiResult } from '../api/client';

const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

function harness(replies: ApiResult<string>[]) {
  const keys: string[] = [];
  const send = vi.fn(async (key: string): Promise<ApiResult<string>> => {
    keys.push(key);
    return replies.shift() ?? { ok: true, status: 200, data: 'x', next: null, etag: null };
  });
  const hook = renderHook(() => useIdempotentSubmit(send));
  return { keys, hook, send };
}

const fail = (status: number): ApiResult<string> => ({ ok: false, status, errorText: `HTTP ${status}` });

describe('useIdempotentSubmit key lifecycle', () => {
  afterEach(() => vi.unstubAllGlobals());

  it.each([0, 408, 429, 500, 502, 503])('keeps the key after status %i', async (status) => {
    const { keys, hook } = harness([fail(status)]);
    await act(() => hook.result.current.submit('f'));
    await act(() => hook.result.current.submit('f'));
    expect(keys).toHaveLength(2);
    expect(keys[1]).toBe(keys[0]);
  });

  it.each([200, 400, 404, 409])('drops the key after status %i', async (status) => {
    const reply: ApiResult<string> =
      status === 200 ? { ok: true, status, data: 'x', next: null, etag: null } : fail(status);
    const { keys, hook } = harness([reply]);
    await act(() => hook.result.current.submit('f'));
    await act(() => hook.result.current.submit('f'));
    expect(keys).toHaveLength(2);
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('drops the key when the fingerprint changes', async () => {
    const { keys, hook } = harness([fail(0)]);
    await act(() => hook.result.current.submit('a'));
    await act(() => hook.result.current.submit('b'));
    expect(keys[1]).not.toBe(keys[0]);
  });

  it('generates a v4 UUID without crypto.randomUUID', async () => {
    const real = globalThis.crypto;
    vi.stubGlobal('crypto', { getRandomValues: real.getRandomValues.bind(real) });
    expect(globalThis.crypto.randomUUID).toBeUndefined();
    const { keys, hook } = harness([]);
    await act(() => hook.result.current.submit('f'));
    expect(keys[0]).toMatch(UUID_V4);
  });

  it('is usable again after send throws', async () => {
    const send = vi
      .fn<(key: string) => Promise<ApiResult<string>>>()
      .mockRejectedValueOnce(new Error('boom'))
      .mockResolvedValueOnce({ ok: true, status: 200, data: 'x', next: null, etag: null });
    const hook = renderHook(() => useIdempotentSubmit(send));
    await act(async () => {
      await hook.result.current.submit('f').catch(() => undefined);
    });
    const r = await act(() => hook.result.current.submit('f'));
    expect(r?.phase).toBe('done');
  });
});
