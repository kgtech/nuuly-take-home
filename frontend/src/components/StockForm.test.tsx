import { describe, expect, it, vi } from 'vitest';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server, store, TEXT } from '../test/server';
import { StockForm } from './StockForm';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

describe('StockForm add', () => {
  it('adds stock, creating the SKU, and reports the new quantity', async () => {
    const user = userEvent.setup();
    const onDone = vi.fn();
    render(<StockForm operation="add" skuId="new-1" onSuccess={onDone} />);
    await user.type(screen.getByLabelText(/quantity/i), '5');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    await waitFor(() => expect(onDone).toHaveBeenCalledWith({ skuId: 'new-1', quantity: 5 }));
    expect(screen.getByRole('status')).toHaveTextContent(/added 5.*new-1.*now 5/i);
    const key = store.requests[0]?.headers.get('Idempotency-Key');
    expect(key).toMatch(UUID);
    expect(screen.getByRole('status')).toHaveTextContent(`Idempotency-Key ${key}`);
  });

  it('shows the server error text verbatim', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/inventory/:skuId', () =>
        new HttpResponse(TEXT.invalid, { status: 400, headers: { 'Content-Type': 'text/plain' } }),
      ),
    );
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '5');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.invalid);
  });

  it('has an accessible form name', () => {
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    expect(screen.getByRole('form', { name: 'Add stock' })).toBeInTheDocument();
  });

  it('answers a quantity the server is certain to reject with the server text and no request', async () => {
    const user = userEvent.setup();
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    const input = screen.getByLabelText(/quantity/i);
    await user.type(input, '0');
    expect(screen.getByText(/whole number of at least 1/i)).toBeInTheDocument();
    expect(input).toHaveAttribute('aria-invalid', 'true');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.invalid);
    expect(store.requests).toHaveLength(0);
    await user.clear(input);
    await user.type(input, '2147483648');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    expect(store.requests).toHaveLength(0);
  });

  it('answers a malformed skuId with the server text and no request', async () => {
    const user = userEvent.setup();
    render(<StockForm operation="add" skuId="bad id" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '1');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.invalid);
    cleanup();
    render(<StockForm operation="purchase" skuId="" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '1');
    await user.click(screen.getByRole('button', { name: /^purchase$/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    expect(store.requests).toHaveLength(0);
  });

  it('drops the Idempotency-Key when the quantity changes after a network failure', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => HttpResponse.error(), { once: true }));
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    const input = screen.getByLabelText(/quantity/i);
    await user.type(input, '2');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    await screen.findByRole('alert');
    await user.clear(input);
    await user.type(input, '3');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(2);
    expect(store.requests[1]?.headers.get('Idempotency-Key')).not.toBe(
      store.requests[0]?.headers.get('Idempotency-Key'),
    );
  });

  it('reuses the Idempotency-Key after a network failure and regenerates it after a response', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => HttpResponse.error(), { once: true }));
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '2');
    const button = screen.getByRole('button', { name: /add stock/i });
    await user.click(button);
    expect(await screen.findByRole('alert')).toHaveTextContent(/network/i);
    await user.click(button);
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(2);
    const k1 = store.requests[0]?.headers.get('Idempotency-Key');
    const k2 = store.requests[1]?.headers.get('Idempotency-Key');
    expect(k1).toMatch(UUID);
    expect(k2).toBe(k1);

    await user.type(screen.getByLabelText(/quantity/i), '2');
    await user.click(button);
    await waitFor(() => expect(store.requests).toHaveLength(3));
    expect(store.requests[2]?.headers.get('Idempotency-Key')).not.toBe(k1);
  });

  it('sends one request on a double click and disables the button while in flight', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    server.use(
      http.post('*/inventory/:skuId', async () => {
        await new Promise<void>((r) => (release = r));
        return HttpResponse.json({ skuId: 'x', quantity: 2 });
      }),
    );
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '2');
    const button = screen.getByRole('button', { name: /add stock/i });
    await user.dblClick(button);
    await waitFor(() => expect(button).toBeDisabled());
    release();
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(1);
    expect(button).toBeEnabled();
  });

  it('moves focus to the result and clears the field after success', async () => {
    const user = userEvent.setup();
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    const input = screen.getByLabelText(/quantity/i);
    await user.type(input, '2');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    const status = await screen.findByRole('status');
    expect(status).toHaveFocus();
    expect(input).toHaveValue(null);
  });
});

describe('StockForm 5xx', () => {
  it('shows HTTP 502 for an empty body and reuses the key on the retry', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => new HttpResponse(null, { status: 502 }), { once: true }));
    render(<StockForm operation="add" skuId="x" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '2{Enter}');
    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 502');
    await user.click(screen.getByRole('button', { name: /add stock/i }));
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(2);
    expect(store.requests[1]?.headers.get('Idempotency-Key')).toBe(store.requests[0]?.headers.get('Idempotency-Key'));
  });
});

describe('StockForm purchase', () => {
  it('purchases and reports the remaining quantity', async () => {
    const user = userEvent.setup();
    store.seed({ A: 5 });
    render(<StockForm operation="purchase" skuId="A" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '3');
    await user.click(screen.getByRole('button', { name: /^purchase$/i }));
    expect(await screen.findByRole('status')).toHaveTextContent(/purchased 3.*A.*2 left/i);
    expect(store.requests[0]?.url).toMatch(/\/inventory\/A\/purchase$/);
  });

  it('shows "Insufficient inventory" verbatim', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1 });
    render(<StockForm operation="purchase" skuId="A" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '3');
    await user.click(screen.getByRole('button', { name: /^purchase$/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.insufficient);
  });

  it('shows "SKU not found" verbatim', async () => {
    const user = userEvent.setup();
    render(<StockForm operation="purchase" skuId="Z" onSuccess={() => {}} />);
    await user.type(screen.getByLabelText(/quantity/i), '1');
    await user.click(screen.getByRole('button', { name: /^purchase$/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
  });
});
