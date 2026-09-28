import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { store, TEXT } from './test/server';
import { App } from './App';
import { SKU_ID_EMPTY_TO_OPEN, SKU_ID_INVALID } from './validation';

describe('App routing', () => {
  it('shows the list at #/ and navigates to a SKU', async () => {
    const user = userEvent.setup();
    store.seed({ A: 4 });
    render(<App />);
    await user.click(await screen.findByRole('link', { name: 'A' }));
    expect(await screen.findByRole('heading', { name: 'A' })).toBeInTheDocument();
    expect(window.location.hash).toBe('#/sku/A');
  });

  it('finds a SKU by id: Open is unavailable with a reason until the id is valid, and no alert is shown', async () => {
    const user = userEvent.setup();
    render(<App />);
    const input = await screen.findByRole('textbox', { name: 'SKU ID' });
    const open = screen.getByRole('button', { name: /open/i });
    const hint = document.getElementById(input.getAttribute('aria-describedby')!)!;

    expect(hint).toHaveTextContent(SKU_ID_EMPTY_TO_OPEN);
    expect(open).toHaveAttribute('aria-disabled', 'true');
    expect(open.getAttribute('aria-describedby')!.split(' ')).toContain(hint.id);
    await user.click(open);
    await user.type(input, '{Enter}');
    expect(window.location.hash).toBe('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();

    await user.type(input, 'bad id');
    expect(hint).toHaveTextContent(SKU_ID_INVALID);
    expect(hint).toHaveTextContent(/letters, digits/i);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(open).toHaveAttribute('aria-disabled', 'true');
    await user.click(open);
    expect(window.location.hash).toBe('');
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.queryByText(TEXT.notFound)).not.toBeInTheDocument();

    await user.clear(input);
    await user.type(input, 'ok-1');
    expect(hint).toBeEmptyDOMElement();
    expect(open).not.toHaveAttribute('aria-disabled');
    await user.click(open);
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
    // A max-width caps, it never forces; width and min-width must fit a phone.
    for (const m of css.matchAll(/(?:^|[^-])(?:min-)?width\s*:\s*(\d+)px/gm)) {
      expect(Number(m[1])).toBeLessThanOrEqual(375);
    }
    // Grid tracks wider than a phone column must be wrapped in min(…, 100%) so they shrink.
    for (const m of css.matchAll(/grid-template-columns\s*:([^;]+);/g)) {
      for (const px of (m[1] ?? '').matchAll(/(min\()?\s*(\d+)px/g)) {
        if (Number(px[2]) > 160) expect(px[1]).toBe('min(');
      }
    }
    // Literal paddings stay small; the large desktop paddings are tokens the phone media query shrinks.
    for (const m of css.matchAll(/(?:^|[^-])padding[^:]*:\s*([^;]+);/gm)) {
      for (const px of (m[1] ?? '').matchAll(/(\d+)px/g)) expect(Number(px[1])).toBeLessThanOrEqual(24);
    }
    expect(css).toMatch(/@media \(max-width: \d+px\)/);
    expect(css).toMatch(/max-width/);
    for (const m of css.matchAll(/min-height:\s*(\d+)px/g)) expect(Number(m[1])).toBeGreaterThanOrEqual(44);
    expect(css).toMatch(/min-height:\s*4[4-9]px/);
    // Unavailable buttons are aria-disabled, not disabled, so the stylesheet must style that state.
    expect(css).toMatch(/button\[aria-disabled=['"]true['"]\]/);
    // Light only (FE26): no dark-mode tokens.
    expect(css).not.toMatch(/prefers-color-scheme/);
  });
});
