import { describe, expect, it } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server, store, TEXT } from '../test/server';
import { InventoryList } from './InventoryList';

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
    const limit = screen.getByLabelText(/page size/i);
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
