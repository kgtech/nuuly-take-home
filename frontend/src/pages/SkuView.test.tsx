import { describe, expect, it } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { store, TEXT } from '../test/server';
import { SkuView } from './SkuView';

const form = (name: string) => within(screen.getByRole('form', { name }));

describe('SkuView', () => {
  it('loads and shows one SKU', async () => {
    store.seed({ 'shoe-1': 7 });
    render(<SkuView skuId="shoe-1" />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i);
    expect(await screen.findByRole('heading', { name: 'shoe-1' })).toBeInTheDocument();
    expect(screen.getByTestId('quantity')).toHaveTextContent('7');
    expect(screen.getByText('Available')).toBeInTheDocument();
  });

  it.each([
    [0, 'Rented out'],
    [2, 'Almost gone'],
    [9, 'Available'],
  ])('shows the same badge as the list for quantity %i', async (quantity, badge) => {
    store.seed({ B: quantity });
    render(<SkuView skuId="B" />);
    expect(await screen.findByTestId('quantity')).toHaveTextContent(String(quantity));
    expect(screen.getByText(badge)).toBeInTheDocument();
  });

  it('answers "." without a request, since the server is certain to reject it', async () => {
    render(<SkuView skuId="." />);
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    expect(store.requests).toHaveLength(0);
  });

  it('owns one outcome area: the latest action replaces the other form\'s message', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1 });
    render(<SkuView skuId="A" />);
    await screen.findByTestId('quantity');
    await user.type(form('Add stock').getByLabelText(/quantity/i), '4{Enter}');
    await waitFor(() => expect(screen.getByTestId('quantity')).toHaveTextContent('5'));
    expect(screen.getByRole('status')).toHaveTextContent('Added 4 to A: now 5.');
    await user.type(form('Purchase').getByLabelText(/quantity/i), '2{Enter}');
    await waitFor(() => expect(screen.getByTestId('quantity')).toHaveTextContent('3'));
    expect(screen.getAllByRole('status')).toHaveLength(1);
    expect(screen.getByRole('status')).toHaveTextContent('Purchased 2 of A: 3 left.');
    expect(screen.queryByText(/now 5/)).not.toBeInTheDocument();
    const keys = store.requests.filter((r) => r.method === 'POST').map((r) => r.headers.get('Idempotency-Key'));
    expect(new Set(keys).size).toBe(2);
  });

  it('after "Insufficient inventory" re-fetches the count and says how many are on hand', async () => {
    const user = userEvent.setup();
    store.seed({ A: 5 });
    render(<SkuView skuId="A" />);
    await screen.findByTestId('quantity');
    store.items.set('A', 1); // another client bought 4 meanwhile
    await user.type(form('Purchase').getByLabelText(/quantity/i), '3{Enter}');
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.insufficient)).toBe(true);
    expect(alert).toHaveTextContent('Only 1 on hand now. Lower the quantity or add stock.');
    expect(screen.getByTestId('quantity')).toHaveTextContent('1');
    expect(store.requests.filter((r) => r.method === 'GET')).toHaveLength(2);
  });

  it('a later add replaces a stale "Insufficient inventory" message', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1 });
    render(<SkuView skuId="A" />);
    await screen.findByTestId('quantity');
    await user.type(form('Purchase').getByLabelText(/quantity/i), '3{Enter}');
    await screen.findByRole('alert');
    await user.type(form('Add stock').getByLabelText(/quantity/i), '2{Enter}');
    await screen.findByRole('status');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByTestId('quantity')).toHaveTextContent('3');
  });

  it('shows the 404 text verbatim, offers to add stock, and makes Purchase unavailable until an add succeeds', async () => {
    const user = userEvent.setup();
    render(<SkuView skuId="nope" />);
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    const buy = screen.getByRole('button', { name: /^purchase$/i });
    expect(buy).toHaveAttribute('aria-disabled', 'true');
    const reason = screen.getByText('Add stock first to create this SKU.');
    expect(buy.getAttribute('aria-describedby')!.split(' ')).toContain(reason.id);
    await user.type(form('Purchase').getByLabelText(/quantity/i), '1{Enter}');
    expect(store.requests.filter((r) => r.method === 'POST')).toHaveLength(0);

    await user.type(form('Add stock').getByLabelText(/quantity/i), '2{Enter}');
    await screen.findByRole('status');
    expect(screen.getByTestId('quantity')).toHaveTextContent('2');
    expect(screen.queryByText('Add stock first to create this SKU.')).not.toBeInTheDocument();
    expect(form('Purchase').getByLabelText(/quantity/i)).toHaveValue(1); // kept from the blocked attempt
    await user.type(form('Purchase').getByLabelText(/quantity/i), '{Enter}');
    await waitFor(() => expect(screen.getByTestId('quantity')).toHaveTextContent('1'));
  });
});
