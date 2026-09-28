import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { useState } from 'react';
import { server, store } from './server';
import { StockForm, StockOutcomeView, type StockOutcome } from '../components/StockForm';
import { GUIDANCE } from '../components/Messages';
import { SkuView } from '../pages/SkuView';
import { InventoryList } from '../pages/InventoryList';

const odd = () =>
  new HttpResponse('Something odd happened', { status: 418, headers: { 'Content-Type': 'text/plain' } });
const boom = () =>
  new HttpResponse('Internal server error', { status: 500, headers: { 'Content-Type': 'text/plain' } });

function Form({ operation }: { operation: 'add' | 'purchase' }) {
  const [outcome, setOutcome] = useState<StockOutcome | null>(null);
  return (
    <>
      <StockForm operation={operation} skuId="A" onOutcome={setOutcome} />
      <StockOutcomeView outcome={outcome} />
    </>
  );
}

describe('arbitrary server error texts are shown verbatim, first, with one guidance line by status class', () => {
  for (const [label, reply, text, guidance] of [
    ['odd', odd, 'Something odd happened', null],
    ['500', boom, 'Internal server error', GUIDANCE.retrySafe],
  ] as const) {
    it(`add: ${label}`, async () => {
      server.use(http.post('*/inventory/:skuId', reply));
      const user = userEvent.setup();
      render(<Form operation="add" />);
      await user.type(screen.getByLabelText(/quantity/i), '1');
      await user.click(screen.getByRole('button', { name: 'Add stock' }));
      const alert = await screen.findByRole('alert');
      expect(alert.textContent!.startsWith(text)).toBe(true);
      expect(alert.textContent!.replace(text, '').trim()).toBe(guidance ?? '');
    });
    it(`purchase: ${label}`, async () => {
      server.use(http.post('*/inventory/:skuId/purchase', reply));
      const user = userEvent.setup();
      render(<Form operation="purchase" />);
      await user.type(screen.getByLabelText(/quantity/i), '1');
      await user.click(screen.getByRole('button', { name: 'Purchase' }));
      const alert = await screen.findByRole('alert');
      expect(alert.textContent!.startsWith(text)).toBe(true);
      expect(alert.textContent!.replace(text, '').trim()).toBe(guidance ?? '');
    });
    it(`view: ${label}`, async () => {
      server.use(http.get('*/inventory/:skuId', reply));
      render(<SkuView skuId="A" />);
      expect(await screen.findByRole('alert')).toHaveTextContent(text);
    });
    it(`list: ${label}`, async () => {
      server.use(http.get('*/inventory', reply));
      render(<InventoryList />);
      expect(await screen.findByRole('alert')).toHaveTextContent(text);
    });
  }
});

describe('network failure shows an error state with Retry', () => {
  it('SkuView', async () => {
    store.seed({ A: 2 });
    server.use(http.get('*/inventory/:skuId', () => HttpResponse.error(), { once: true }));
    const user = userEvent.setup();
    render(<SkuView skuId="A" />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/network/i);
    await user.click(screen.getByRole('button', { name: /retry/i }));
    expect(await screen.findByTestId('quantity')).toHaveTextContent('2');
  });
  it('InventoryList', async () => {
    store.seed({ A: 2 });
    server.use(http.get('*/inventory', () => HttpResponse.error(), { once: true }));
    const user = userEvent.setup();
    render(<InventoryList />);
    expect(await screen.findByRole('alert')).toHaveTextContent(/network/i);
    await user.click(screen.getByRole('button', { name: /retry/i }));
    expect(await screen.findByRole('link', { name: 'A' })).toBeInTheDocument();
  });
});
