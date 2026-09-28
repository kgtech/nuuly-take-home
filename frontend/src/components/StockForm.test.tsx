import { describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useState } from 'react';
import { server, store, TEXT } from '../test/server';
import { StockForm, StockOutcomeView, type StockOutcome } from './StockForm';
import { GUIDANCE } from './Messages';
import { QUANTITY_REASON, SKU_ID_INVALID } from '../validation';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

/** A parent that owns the outcome area, as SkuView and AddStockPage do. */
function Harness({
  operation,
  skuId,
  onOutcome,
  unavailable,
}: {
  operation: 'add' | 'purchase';
  skuId: string;
  onOutcome?: (o: StockOutcome) => void;
  unavailable?: string | null;
}) {
  const [outcome, setOutcome] = useState<StockOutcome | null>(null);
  return (
    <>
      <StockForm
        operation={operation}
        skuId={skuId}
        unavailable={unavailable}
        onOutcome={(o) => {
          setOutcome(o);
          onOutcome?.(o);
        }}
      />
      <StockOutcomeView outcome={outcome} />
    </>
  );
}

const qty = () => screen.getByLabelText(/quantity/i);
const button = (name: RegExp) => screen.getByRole('button', { name });
const hintOf = (input: HTMLElement) => {
  const ids = input.getAttribute('aria-describedby');
  expect(ids).toBeTruthy();
  return document.getElementById(ids!.split(' ')[0]!)!;
};

describe('StockForm unavailable button (review items 1, 2, 4)', () => {
  it.each([
    ['empty', '', QUANTITY_REASON],
    ['letters (a number input turns them into empty)', 'abc', QUANTITY_REASON],
    ['zero', '0', QUANTITY_REASON],
    ['negative', '-1', QUANTITY_REASON],
    ['decimal', '1.5', QUANTITY_REASON],
    ['over the cap', '2147483648', `At most ${(2_147_483_647).toLocaleString()}.`],
  ])('quantity %s: reason under the field, aria-disabled button, no request', async (_label, value, reason) => {
    const user = userEvent.setup();
    render(<Harness operation="add" skuId="x" />);
    const input = qty();
    if (value !== '') await user.type(input, value);
    const hint = hintOf(input);
    expect(hint).toHaveTextContent(reason);
    const submit = button(/add stock/i);
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(submit).not.toBeDisabled();
    expect(submit.getAttribute('aria-describedby')!.split(' ')).toContain(hint.id);
    await user.click(submit);
    await user.type(input, '{Enter}');
    expect(store.requests).toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    // An untouched empty field is not flagged invalid; a filled wrong one is.
    const empty = (input as HTMLInputElement).value === '';
    expect(input.getAttribute('aria-invalid')).toBe(empty ? null : 'true');
  });

  it('becomes available once the quantity is valid and always keeps the hint element linked', async () => {
    const user = userEvent.setup();
    render(<Harness operation="add" skuId="x" />);
    const input = qty();
    const hint = hintOf(input);
    await user.type(input, '3');
    expect(hint).toBeEmptyDOMElement();
    expect(input).toHaveAttribute('aria-describedby', hint.id);
    expect(button(/add stock/i)).not.toHaveAttribute('aria-disabled');
    await user.click(button(/add stock/i));
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(1);
  });

  it('shows the SKU ID reason under the button when the id fails G11 and sends nothing', async () => {
    const user = userEvent.setup();
    render(<Harness operation="add" skuId="bad id" />);
    await user.type(qty(), '1');
    const submit = button(/add stock/i);
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    const ids = submit.getAttribute('aria-describedby')!.split(' ');
    const texts = ids.map((i) => document.getElementById(i)?.textContent);
    expect(texts).toContain(SKU_ID_INVALID);
    await user.click(submit);
    expect(store.requests).toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('is unavailable with the parent reason (purchase on a SKU the page says is missing)', async () => {
    const user = userEvent.setup();
    const reason = 'Add stock first to create this SKU.';
    render(<Harness operation="purchase" skuId="x" unavailable={reason} />);
    await user.type(qty(), '1');
    const submit = button(/^purchase$/i);
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(screen.getByText(reason)).toBeInTheDocument();
    expect(submit.getAttribute('aria-describedby')!.split(' ')).toContain(screen.getByText(reason).id);
    await user.click(submit);
    await user.keyboard('{Enter}');
    expect(store.requests).toHaveLength(0);
  });

  it('in flight: aria-disabled and aria-busy, read-only input, never native disabled, one request on a double click', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    server.use(
      http.post('*/inventory/:skuId', async () => {
        await new Promise<void>((r) => (release = r));
        return HttpResponse.json({ skuId: 'x', quantity: 2 });
      }),
    );
    render(<Harness operation="add" skuId="x" />);
    await user.type(qty(), '2');
    const submit = button(/add stock/i);
    await user.dblClick(submit);
    await waitFor(() => expect(submit).toHaveAttribute('aria-disabled', 'true'));
    expect(submit).not.toBeDisabled();
    expect(screen.getByRole('form', { name: 'Add stock' })).toHaveAttribute('aria-busy', 'true');
    expect(qty()).toHaveAttribute('readonly');
    expect(qty()).not.toBeDisabled();
    await user.keyboard('{Enter}');
    release();
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(1);
    expect(submit).not.toHaveAttribute('aria-disabled');
    expect(qty()).not.toHaveAttribute('readonly');
  });
});

describe('StockForm add', () => {
  it('adds stock, creating the SKU, and reports the outcome with the key behind Request reference', async () => {
    const user = userEvent.setup();
    const onOutcome = vi.fn();
    render(<Harness operation="add" skuId="new-1" onOutcome={onOutcome} />);
    await user.type(qty(), '5');
    await user.click(button(/add stock/i));
    const status = await screen.findByRole('status');
    expect(onOutcome).toHaveBeenCalledWith(
      expect.objectContaining({ kind: 'done', operation: 'add', sent: 5, item: { skuId: 'new-1', quantity: 5 } }),
    );
    expect(status).toHaveTextContent('Added 5 to new-1: now 5.');
    const key = store.requests[0]?.headers.get('Idempotency-Key');
    expect(key).toMatch(UUID);
    const details = status.querySelector('details')!;
    expect(details).not.toHaveAttribute('open');
    expect(details.querySelector('summary')).toHaveTextContent('Request reference');
    expect(details).toHaveTextContent(`Idempotency-Key ${key}`);
    expect(status.textContent!.replace(details.textContent!, '')).not.toContain(key);
  });

  it('shows a server 400 verbatim with the check-the-values line', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/inventory/:skuId', () =>
        new HttpResponse(TEXT.invalid, { status: 400, headers: { 'Content-Type': 'text/plain' } }),
      ),
    );
    render(<Harness operation="add" skuId="x" />);
    await user.type(qty(), '5');
    await user.click(button(/add stock/i));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.invalid)).toBe(true);
    expect(alert).toHaveTextContent(GUIDANCE.refused);
  });

  it('has an accessible form name', () => {
    render(<Harness operation="add" skuId="x" />);
    expect(screen.getByRole('form', { name: 'Add stock' })).toBeInTheDocument();
  });

  it('drops the Idempotency-Key when the quantity changes after a network failure', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => HttpResponse.error(), { once: true }));
    render(<Harness operation="add" skuId="x" />);
    const input = qty();
    await user.type(input, '2');
    await user.click(button(/add stock/i));
    await screen.findByRole('alert');
    await user.clear(input);
    await user.type(input, '3');
    await user.click(button(/add stock/i));
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(2);
    expect(store.requests[1]?.headers.get('Idempotency-Key')).not.toBe(
      store.requests[0]?.headers.get('Idempotency-Key'),
    );
  });

  it('reuses the Idempotency-Key after a network failure, says a retry is safe, and regenerates it after a response', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => HttpResponse.error(), { once: true }));
    render(<Harness operation="add" skuId="x" />);
    await user.type(qty(), '2');
    const submit = button(/add stock/i);
    await user.click(submit);
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/network/i);
    expect(alert).toHaveTextContent(GUIDANCE.retrySafe);
    await user.click(submit);
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(2);
    const k1 = store.requests[0]?.headers.get('Idempotency-Key');
    const k2 = store.requests[1]?.headers.get('Idempotency-Key');
    expect(k1).toMatch(UUID);
    expect(k2).toBe(k1);

    await user.type(qty(), '2');
    await user.click(submit);
    await waitFor(() => expect(store.requests).toHaveLength(3));
    expect(store.requests[2]?.headers.get('Idempotency-Key')).not.toBe(k1);
  });

  it('moves focus to the result and clears the field after success', async () => {
    const user = userEvent.setup();
    render(<Harness operation="add" skuId="x" />);
    const input = qty();
    await user.type(input, '2');
    await user.click(button(/add stock/i));
    const status = await screen.findByRole('status');
    expect(status).toHaveFocus();
    expect(input).toHaveValue(null);
  });
});

describe('StockForm 5xx', () => {
  it('shows HTTP 502 for an empty body with the retry line and reuses the key on the retry', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => new HttpResponse(null, { status: 502 }), { once: true }));
    render(<Harness operation="add" skuId="x" />);
    await user.type(qty(), '2{Enter}');
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith('HTTP 502')).toBe(true);
    expect(alert).toHaveTextContent(GUIDANCE.retrySafe);
    await user.click(button(/add stock/i));
    await screen.findByRole('status');
    expect(store.requests).toHaveLength(2);
    expect(store.requests[1]?.headers.get('Idempotency-Key')).toBe(store.requests[0]?.headers.get('Idempotency-Key'));
  });
});

describe('StockForm purchase', () => {
  it('purchases and reports the remaining quantity', async () => {
    const user = userEvent.setup();
    store.seed({ A: 5 });
    render(<Harness operation="purchase" skuId="A" />);
    await user.type(qty(), '3');
    await user.click(button(/^purchase$/i));
    expect(await screen.findByRole('status')).toHaveTextContent('Purchased 3 of A: 2 left.');
    expect(store.requests[0]?.url).toMatch(/\/inventory\/A\/purchase$/);
  });

  it('shows "Insufficient inventory" verbatim as the first line', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1 });
    render(<Harness operation="purchase" skuId="A" />);
    await user.type(qty(), '3');
    await user.click(button(/^purchase$/i));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.insufficient)).toBe(true);
  });

  it('shows "SKU not found" verbatim with no guidance line', async () => {
    const user = userEvent.setup();
    render(<Harness operation="purchase" skuId="Z" />);
    await user.type(qty(), '1');
    await user.click(button(/^purchase$/i));
    expect(await screen.findByRole('alert')).toHaveTextContent(new RegExp(`^${TEXT.notFound}$`));
  });
});
