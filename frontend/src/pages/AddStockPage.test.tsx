import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AddStockPage } from './AddStockPage';
import { store } from '../test/server';
import { SKU_ID_EMPTY, SKU_ID_INVALID } from '../validation';

describe('AddStockPage', () => {
  it('keeps the quantity and the last result while the SKU ID is edited', async () => {
    const user = userEvent.setup();
    render(<AddStockPage />);
    await user.type(screen.getByLabelText(/quantity/i), '4');
    await user.type(screen.getByLabelText('SKU ID'), 'new-9');
    expect(screen.getByLabelText(/quantity/i)).toHaveValue(4);
    await user.click(screen.getByRole('button', { name: 'Add stock' }));
    expect(await screen.findByRole('status')).toHaveTextContent('Added 4 to new-9: now 4.');
    await user.type(screen.getByLabelText('SKU ID'), '9');
    expect(screen.getByRole('status')).toHaveTextContent('Added 4 to new-9: now 4.');
    expect(screen.getByRole('link', { name: 'View new-9' })).toBeInTheDocument();
  });

  it.each([
    ['empty', '', SKU_ID_EMPTY],
    ['malformed', 'bad id', SKU_ID_INVALID],
  ])('a(n) %s SKU ID gets its own hint under the SKU field and makes the button unavailable', async (_l, value, reason) => {
    const user = userEvent.setup();
    render(<AddStockPage />);
    const sku = screen.getByLabelText('SKU ID');
    if (value !== '') await user.type(sku, value);
    await user.type(screen.getByLabelText(/quantity/i), '3');
    const hint = document.getElementById(sku.getAttribute('aria-describedby')!)!;
    expect(hint).toHaveTextContent(reason);
    const submit = screen.getByRole('button', { name: 'Add stock' });
    expect(submit).toHaveAttribute('aria-disabled', 'true');
    expect(submit.getAttribute('aria-describedby')!.split(' ')).toContain(hint.id);
    await user.click(submit);
    await user.type(screen.getByLabelText(/quantity/i), '{Enter}');
    expect(store.requests).toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(sku.getAttribute('aria-invalid')).toBe(value === '' ? null : 'true');
  });
});
