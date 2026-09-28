import { describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { store, TEXT } from '../test/server';
import { SkuView } from './SkuView';

describe('SkuView', () => {
  it('loads and shows one SKU', async () => {
    store.seed({ 'shoe-1': 7 });
    render(<SkuView skuId="shoe-1" />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i);
    expect(await screen.findByRole('heading', { name: 'shoe-1' })).toBeInTheDocument();
    expect(screen.getByTestId('quantity')).toHaveTextContent('7');
    expect(screen.queryByText('Rented out')).not.toBeInTheDocument();
  });

  it('marks a SKU at 0 as rented out', async () => {
    store.seed({ 'shoe-0': 0 });
    render(<SkuView skuId="shoe-0" />);
    expect(await screen.findByTestId('quantity')).toHaveTextContent('0');
    expect(screen.getByText('Rented out')).toBeInTheDocument();
  });

  it('answers "." without a request, since the server is certain to reject it', async () => {
    render(<SkuView skuId="." />);
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    expect(store.requests).toHaveLength(0);
  });

  it('shows the quantity after an add and a purchase', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1 });
    render(<SkuView skuId="A" />);
    await screen.findByTestId('quantity');
    await user.type(within(screen.getByRole('form', { name: 'Add stock' })).getByLabelText(/quantity/i), '4{Enter}');
    await waitFor(() => expect(screen.getByTestId('quantity')).toHaveTextContent('5'));
    await user.type(within(screen.getByRole('form', { name: 'Purchase' })).getByLabelText(/quantity/i), '2{Enter}');
    await waitFor(() => expect(screen.getByTestId('quantity')).toHaveTextContent('3'));
    const keys = store.requests.filter((r) => r.method === 'POST').map((r) => r.headers.get('Idempotency-Key'));
    expect(new Set(keys).size).toBe(2);
  });

  it('shows the 404 text verbatim and still offers to add stock', async () => {
    render(<SkuView skuId="nope" />);
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    expect(screen.getByRole('button', { name: /add stock/i })).toBeInTheDocument();
  });
});
