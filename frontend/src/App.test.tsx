import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
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
    const input = await screen.findByRole('textbox', { name: 'SKU ID' });
    await user.type(input, 'bad id');
    expect(screen.getByText(/letters, digits/i)).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /open/i }));
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    expect(window.location.hash).toBe('');
    await user.clear(input);
    await user.type(input, 'ok-1');
    await user.click(screen.getByRole('button', { name: /open/i }));
    expect(window.location.hash).toBe('#/sku/ok-1');
  });

  it('shows the SKU page for a malformed hash escape instead of a blank page', async () => {
    window.location.hash = '#/sku/50%off';
    render(<App />);
    expect(await screen.findByRole('heading', { name: '50%off' })).toBeInTheDocument();
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
  });

  it('renders an unknown route as not found with a way home', async () => {
    window.location.hash = '#/nothing/here';
    render(<App />);
    expect(await screen.findByText(/page not found/i)).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'inventory' })).toHaveAttribute('href', '#/');
  });
});

describe('phone width', () => {
  it('uses no fixed pixel widths wider than a phone in the stylesheet', () => {
    const css = readFileSync(resolve(process.cwd(), 'src/styles.css'), 'utf8');
    for (const m of css.matchAll(/(?:min-|max-)?width\s*:\s*(\d+)px/g)) {
      expect(Number(m[1])).toBeLessThanOrEqual(375);
    }
    for (const m of css.matchAll(/grid-template-columns\s*:[^;]*?(\d+)px/g)) {
      expect(Number(m[1])).toBeLessThanOrEqual(160);
    }
    for (const m of css.matchAll(/padding[^:]*:\s*([^;]+);/g)) {
      for (const px of (m[1] ?? '').matchAll(/(\d+)px/g)) expect(Number(px[1])).toBeLessThanOrEqual(24);
    }
    expect(css).toMatch(/max-width/);
    expect(css).toMatch(/min-height:\s*44px/);
  });
});
