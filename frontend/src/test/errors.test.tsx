import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server, store } from './server';
import { StockForm } from '../components/StockForm';
import { SkuView } from '../pages/SkuView';
import { InventoryList } from '../pages/InventoryList';

const odd = () =>
  new HttpResponse('Something odd happened', { status: 418, headers: { 'Content-Type': 'text/plain' } });
const boom = () =>
  new HttpResponse('Internal server error', { status: 500, headers: { 'Content-Type': 'text/plain' } });

describe('arbitrary server error texts are shown verbatim', () => {
  for (const [label, reply, text] of [
    ['odd', odd, 'Something odd happened'],
    ['500', boom, 'Internal server error'],
  ] as const) {
    it(`add: ${label}`, async () => {
      server.use(http.post('*/inventory/:skuId', reply));
      const user = userEvent.setup();
      render(<StockForm operation="add" skuId="A" onSuccess={() => {}} />);
      await user.type(screen.getByLabelText(/quantity/i), '1');
      await user.click(screen.getByRole('button', { name: 'Add stock' }));
      expect(await screen.findByRole('alert')).toHaveTextContent(text);
    });
    it(`purchase: ${label}`, async () => {
      server.use(http.post('*/inventory/:skuId/purchase', reply));
      const user = userEvent.setup();
      render(<StockForm operation="purchase" skuId="A" onSuccess={() => {}} />);
      await user.type(screen.getByLabelText(/quantity/i), '1');
      await user.click(screen.getByRole('button', { name: 'Purchase' }));
      expect(await screen.findByRole('alert')).toHaveTextContent(text);
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
