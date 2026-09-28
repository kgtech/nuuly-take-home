import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server, store, TEXT } from '../test/server';
import { availability, InventoryList } from './InventoryList';

describe('InventoryList', () => {
  it('shows loading then rows', async () => {
    store.seed({ A: 1, B: 2 });
    render(<InventoryList />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i);
    const rows = await screen.findAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(within(rows[1]!).getByRole('link', { name: 'A' })).toHaveAttribute('href', '#/sku/A');
    expect(rows[1]).toHaveTextContent('1');
    expect(screen.queryByRole('button', { name: /next page/i })).not.toBeInTheDocument();
  });

  it('shows an empty state', async () => {
    render(<InventoryList />);
    expect(await screen.findByText(/no skus yet/i)).toBeInTheDocument();
    expect(screen.getByRole('heading', { level: 3, name: 'The closet is empty' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Add stock' })).toHaveAttribute('href', '#/add');
  });

  it('labels the page and shows an availability badge per row (0 / 1–3 / above 3)', async () => {
    store.seed({ gone: 0, low1: 1, low3: 3, ok4: 4, big: 12345 });
    render(<InventoryList />);
    const rows = await screen.findAllByRole('row');
    expect(screen.getByText('Page 1 · 5 SKUs')).toBeInTheDocument();
    expect(screen.getByRole('columnheader', { name: 'Availability' })).toBeInTheDocument();
    const badge = (name: string) =>
      within(rows.find((r) => within(r).queryByRole('link', { name }))!).getAllByRole('cell')[1]!.textContent;
    expect(badge('gone')).toBe('Rented out');
    expect(badge('low1')).toBe('Almost gone');
    expect(badge('low3')).toBe('Almost gone');
    expect(badge('ok4')).toBe('Available');
    expect(badge('big')).toBe('Available');
    expect(within(rows.find((r) => within(r).queryByRole('link', { name: 'big' }))!).getAllByRole('cell')[2]).toHaveTextContent(
      (12345).toLocaleString(),
    );
  });

  it('labels one SKU in the singular', async () => {
    store.seed({ A: 1 });
    render(<InventoryList />);
    expect(await screen.findByText('Page 1 · 1 SKU')).toBeInTheDocument();
  });

  it('shows the error text verbatim', async () => {
    server.use(
      http.get('*/inventory', () =>
        new HttpResponse(TEXT.invalid, { status: 400, headers: { 'Content-Type': 'text/plain' } }),
      ),
    );
    render(<InventoryList />);
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.invalid);
  });

  it('pages with limit and follows the Link header for the next page', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1, B: 2, C: 3, D: 4, E: 5 });
    render(<InventoryList />);
    await screen.findAllByRole('row');
    const limit = screen.getByLabelText(/per page/i);
    await user.clear(limit);
    await user.type(limit, '2');
    await user.click(screen.getByRole('button', { name: /apply/i }));
    expect(await screen.findByRole('link', { name: 'B' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'C' })).not.toBeInTheDocument();

    const next = screen.getByRole('button', { name: /next page/i });
    await user.click(next);
    expect(await screen.findByRole('link', { name: 'C' })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'D' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'A' })).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /next page/i }));
    expect(await screen.findByRole('link', { name: 'E' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /next page/i })).not.toBeInTheDocument();
  });

  it('lets the user start after a cursor', async () => {
    const user = userEvent.setup();
    store.seed({ A: 1, B: 2, C: 3 });
    render(<InventoryList />);
    await screen.findAllByRole('row');
    await user.type(screen.getByLabelText(/after/i), 'B');
    await user.click(screen.getByRole('button', { name: /apply/i }));
    expect(await screen.findByRole('link', { name: 'C' })).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'A' })).not.toBeInTheDocument();
  });
});

describe('availability thresholds', () => {
  it.each([
    [0, 'Rented out'],
    [1, 'Almost gone'],
    [3, 'Almost gone'],
    [4, 'Available'],
  ])('%i → %s', (quantity, text) => {
    expect(availability(quantity).text).toBe(text);
  });
});
