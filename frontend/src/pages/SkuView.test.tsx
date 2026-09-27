import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { store, TEXT } from '../test/server';
import { SkuView } from './SkuView';

describe('SkuView', () => {
  it('loads and shows one SKU', async () => {
    store.seed({ 'shoe-1': 7 });
    render(<SkuView skuId="shoe-1" />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i);
    expect(await screen.findByRole('heading', { name: 'shoe-1' })).toBeInTheDocument();
    expect(screen.getByTestId('quantity')).toHaveTextContent('7');
  });

  it('shows the 404 text verbatim and still offers to add stock', async () => {
    render(<SkuView skuId="nope" />);
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    expect(screen.getByRole('button', { name: /add stock/i })).toBeInTheDocument();
  });
});
