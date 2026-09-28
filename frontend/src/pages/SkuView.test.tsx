import { describe, expect, it } from 'vitest';
import { cleanup, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { fireEvent } from '@testing-library/react';
import { http, HttpResponse } from 'msw';
import { server, store, TEXT } from '../test/server';
import { SkuView } from './SkuView';
import type { SkuDetails } from '../api/client';

const details: SkuDetails = {
  name: 'Linen dress',
  description: 'A midi dress\nin sand.',
  cost: { amount: 12900, currency: 'USD' },
  images: ['https://img.example/1.jpg', 'https://img.example/2.jpg'],
};

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

  it('ignores a late "Insufficient inventory" re-fetch that resolves after a newer add (F-08)', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1 });
    let gets = 0;
    server.use(
      http.get('*/v2/inventory/:skuId', async () => {
        gets += 1;
        if (gets === 1) return; // the page load falls through to the store
        await new Promise((r) => setTimeout(r, 300)); // the re-fetch after 400 arrives late and stale
        return HttpResponse.json({ skuId: 'A', quantity: 1 }, { headers: { ETag: '"0"' } });
      }),
    );
    render(<SkuView skuId="A" />);
    await screen.findByTestId('quantity');
    await user.type(form('Purchase').getByLabelText(/quantity/i), '3{Enter}');
    await waitFor(() => expect(store.requests.filter((r) => r.method === 'POST')).toHaveLength(1));
    await user.type(form('Add stock').getByLabelText(/quantity/i), '2{Enter}');
    expect(await screen.findByRole('status')).toHaveTextContent('Added 2 to A: now 3.');
    await new Promise((r) => setTimeout(r, 400));
    expect(screen.getByTestId('quantity')).toHaveTextContent('3');
    expect(screen.getByRole('status')).toHaveTextContent('Added 2 to A: now 3.');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });

  it('after an add that follows a non-404 load error, re-fetches GET /v2 instead of showing "No details yet" (F-fe-05)', async () => {
    const user = userEvent.setup();
    store.seedDetails('D-1', 1, details);
    server.use(http.get('*/v2/inventory/:skuId', () => new HttpResponse(null, { status: 502 }), { once: true }));
    render(<SkuView skuId="D-1" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 502');
    await user.type(form('Add stock').getByLabelText(/quantity/i), '4{Enter}');
    expect(await screen.findByRole('heading', { level: 2, name: 'Linen dress' })).toBeInTheDocument();
    expect(screen.getByTestId('quantity')).toHaveTextContent('5');
    expect(screen.queryByText('No details yet')).not.toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Added 4 to D-1: now 5.');
    expect(store.requests.filter((r) => r.method === 'GET')).toHaveLength(2);
  });

  it('when the re-fetch after an add fails too, keeps the quantity and shows a details error with Retry, not "No details yet" (R-04)', async () => {
    const user = userEvent.setup();
    store.seedDetails('D-1', 1, details);
    let gets = 0;
    server.use(
      http.get('*/v2/inventory/:skuId', () => {
        gets += 1;
        return gets <= 2 ? new HttpResponse(null, { status: 502 }) : undefined;
      }),
    );
    render(<SkuView skuId="D-1" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('HTTP 502');
    await user.type(form('Add stock').getByLabelText(/quantity/i), '4{Enter}');
    expect(await screen.findByRole('status')).toHaveTextContent('Added 4 to D-1: now 5.');
    await waitFor(() => expect(store.requests.filter((r) => r.method === 'GET')).toHaveLength(2));
    expect(screen.getByTestId('quantity')).toHaveTextContent('5');
    expect(screen.queryByText('No details yet')).not.toBeInTheDocument();
    const detailsError = await screen.findByText(/could not load the details/i);
    expect(detailsError.closest('[role="alert"]')).toHaveTextContent('HTTP 502');
    await user.click(screen.getByRole('button', { name: /retry details/i }));
    expect(await screen.findByRole('heading', { level: 2, name: 'Linen dress' })).toBeInTheDocument();
    expect(screen.getByTestId('quantity')).toHaveTextContent('5');
  });

  it('formats a large quantity with separators (F-fe-08)', async () => {
    store.seed({ big: 1234567 });
    render(<SkuView skuId="big" />);
    expect(await screen.findByTestId('quantity')).toHaveTextContent((1234567).toLocaleString());
  });

  it('shows the create-on-add note only for a 404, not for another load error (F-12)', async () => {
    render(<SkuView skuId="nope" />);
    await screen.findByRole('alert');
    expect(screen.getByText(/creates the SKU if it does not exist/i)).toBeInTheDocument();
    cleanup();
    server.use(http.get('*/v2/inventory/:skuId', () => new HttpResponse('Internal server error', { status: 500, headers: { 'Content-Type': 'text/plain' } })));
    render(<SkuView skuId="A" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Internal server error');
    expect(screen.queryByText(/creates the SKU if it does not exist/i)).not.toBeInTheDocument();
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

  it('reads GET /v2 and shows the details: name, description, formatted cost, lazy thumbnails, an edit link', async () => {
    store.seedDetails('D-1', 4, details);
    render(<SkuView skuId="D-1" />);
    expect(await screen.findByRole('heading', { level: 2, name: 'Linen dress' })).toBeInTheDocument();
    expect(new URL(store.requests[0]!.url).pathname).toBe('/v2/inventory/D-1');
    expect(screen.getByText(/A midi dress/)).toHaveTextContent('A midi dress in sand.');
    expect(screen.getByTestId('cost')).toHaveTextContent('$129.00');
    const imgs = screen.getAllByRole('img', { name: 'Linen dress' });
    expect(imgs).toHaveLength(2);
    expect(imgs[0]).toHaveAttribute('src', 'https://img.example/1.jpg');
    expect(imgs[0]).toHaveAttribute('loading', 'lazy');
    expect(screen.getByRole('link', { name: 'Edit details' })).toHaveAttribute('href', '#/sku/D-1/edit');
    expect(screen.queryByText('No details yet')).not.toBeInTheDocument();
    expect(screen.getByTestId('quantity')).toHaveTextContent('4');
  });

  it('replaces a broken image with a fallback that keeps the name', async () => {
    store.seedDetails('D-1', 4, details);
    render(<SkuView skuId="D-1" />);
    const [first] = await screen.findAllByRole('img', { name: 'Linen dress' });
    fireEvent.error(first!);
    expect(screen.getAllByRole('img', { name: 'Linen dress' })).toHaveLength(1);
    expect(screen.getByText(/image unavailable/i)).toBeInTheDocument();
  });

  it('a SKU without details says so and links to add them; a SKU without cost or images shows neither', async () => {
    store.seed({ plain: 1 });
    render(<SkuView skuId="plain" />);
    expect(await screen.findByText('No details yet')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add details' })).toHaveAttribute('href', '#/sku/plain/edit');
    expect(screen.queryByRole('link', { name: 'Edit details' })).not.toBeInTheDocument();
    cleanup();
    store.seedDetails('bare', 1, { name: 'Bare' });
    render(<SkuView skuId="bare" />);
    expect(await screen.findByRole('heading', { level: 2, name: 'Bare' })).toBeInTheDocument();
    expect(screen.queryByTestId('cost')).not.toBeInTheDocument();
    expect(screen.queryByRole('img')).not.toBeInTheDocument();
  });

  it('keeps the details on screen after an add and a purchase (v1 responses carry only the quantity)', async () => {
    const user = userEvent.setup();
    store.seedDetails('D-1', 1, details);
    render(<SkuView skuId="D-1" />);
    await screen.findByRole('heading', { level: 2, name: 'Linen dress' });
    await user.type(form('Add stock').getByLabelText(/quantity/i), '4{Enter}');
    await waitFor(() => expect(screen.getByTestId('quantity')).toHaveTextContent('5'));
    expect(screen.getByRole('heading', { level: 2, name: 'Linen dress' })).toBeInTheDocument();
    expect(screen.getByTestId('cost')).toHaveTextContent('$129.00');
  });
});
