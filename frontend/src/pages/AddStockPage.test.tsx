import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AddStockPage } from './AddStockPage';

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
});
