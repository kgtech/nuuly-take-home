import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { store, TEXT } from './test/server';
import { App } from './App';

describe('App routing', () => {
  it('shows the list at #/ and navigates to a SKU', async () => {
    const user = userEvent.setup();
    store.seed({ A: 4 });
    render(<App />);
    await user.click(await screen.findByRole('link', { name: 'A' }));
    expect(await screen.findByRole('heading', { name: 'A' })).toBeInTheDocument();
    expect(window.location.hash).toBe('#/sku/A');
  });

  it('finds a SKU by id from the list page with the skuId pattern as a hint only', async () => {
    const user = userEvent.setup();
    render(<App />);
    const input = await screen.findByLabelText(/sku id/i);
    await user.type(input, 'bad id');
    expect(screen.getByText(/letters, digits/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /open/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
  });

  it('renders an unknown route as not found with a way home', async () => {
    window.location.hash = '#/nothing/here';
    render(<App />);
    expect(await screen.findByText(/page not found/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /inventory/i })).toHaveAttribute('href', '#/');
  });
});

describe('phone width', () => {
  it('uses no fixed pixel widths wider than a phone in the stylesheet', () => {
    const css = readFileSync(new URL('./styles.css', import.meta.url), 'utf8');
    for (const m of css.matchAll(/(?:min-)?width\s*:\s*(\d+)px/g)) {
      expect(Number(m[1])).toBeLessThanOrEqual(375);
    }
    expect(css).toMatch(/max-width/);
  });
});
