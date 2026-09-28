import { describe, expect, it } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server, store, TEXT } from '../test/server';
import { CreateSkuPage } from './CreateSkuPage';
import { GUIDANCE } from '../components/Messages';
import { COST_AMOUNT, COST_PAIR, INITIAL_STOCK, SKU_ID_EMPTY, SKU_ID_INVALID } from '../validation';

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

const field = {
  skuId: () => screen.getByLabelText('SKU ID'),
  name: () => screen.getByLabelText(/^name$/i),
  description: () => screen.getByLabelText(/^description/i),
  amount: () => screen.getByLabelText(/cost amount/i),
  currency: () => screen.getByLabelText(/^currency/i),
  images: () => screen.getByLabelText(/image urls/i),
  initial: () => screen.getByLabelText(/initial stock/i),
};
const submit = () => screen.getByRole('button', { name: 'Create SKU' });
const hintOf = (input: HTMLElement) => document.getElementById(input.getAttribute('aria-describedby')!)!;

async function fillValid(user: ReturnType<typeof userEvent.setup>) {
  await user.type(field.skuId(), 'DRS-1');
  await user.type(field.name(), 'Linen dress');
  await user.type(field.description(), 'A midi dress.');
  await user.type(field.amount(), '12900');
  await user.type(field.currency(), 'USD');
  await user.type(field.images(), 'https://img.example/1.jpg\nhttps://img.example/2.jpg');
}

describe('CreateSkuPage (#/new)', () => {
  it('labels every field, links each hint, and starts unavailable with the SKU ID and name reasons', () => {
    render(<CreateSkuPage />);
    for (const f of Object.values(field)) {
      const input = f();
      expect(input.getAttribute('aria-describedby')).toBeTruthy();
      expect(hintOf(input)).toBeInTheDocument();
    }
    expect(hintOf(field.skuId())).toHaveTextContent(SKU_ID_EMPTY);
    expect(hintOf(field.name())).toHaveTextContent('Enter a name.');
    expect(hintOf(field.initial())).toBeEmptyDOMElement();
    expect(submit()).toHaveAttribute('aria-disabled', 'true');
    expect(submit().getAttribute('aria-describedby')!.split(' ')).toEqual(
      expect.arrayContaining([hintOf(field.skuId()).id, hintOf(field.name()).id]),
    );
    expect(screen.getByRole('form', { name: 'New SKU' })).toBeInTheDocument();
  });

  it.each([
    ['SKU ID', 'skuId', 'bad id', SKU_ID_INVALID],
    ['name', 'name', ' ', 'Enter a name.'],
    ['description', 'description', 'x'.repeat(2001), `At most ${(2000).toLocaleString()} characters.`],
    ['cost amount', 'amount', '-5', COST_AMOUNT],
    ['currency', 'currency', 'usd', 'Enter a three-letter uppercase currency code, e.g. USD.'],
    ['image URLs', 'images', 'https://ok.example/1\nnot a url', 'Line 2: enter an absolute http or https URL.'],
    ['initial stock', 'initial', '-1', INITIAL_STOCK],
  ] as const)('a bad %s shows its reason, makes the button unavailable and sends nothing', async (_l, key, value, reason) => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await fillValid(user);
    expect(submit()).not.toHaveAttribute('aria-disabled');
    const input = field[key]();
    await user.clear(input);
    if (value.trim() !== '' || value === ' ') await user.type(input, value);
    expect(hintOf(input)).toHaveTextContent(reason);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(submit()).toHaveAttribute('aria-disabled', 'true');
    expect(submit().getAttribute('aria-describedby')!.split(' ')).toContain(hintOf(input).id);
    await user.click(submit());
    await user.keyboard('{Enter}');
    expect(store.requests).toHaveLength(0);
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  }, 20_000);

  it('cost needs both an amount and a currency, or neither', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await user.type(field.skuId(), 'A');
    await user.type(field.name(), 'A');
    expect(submit()).not.toHaveAttribute('aria-disabled');
    await user.type(field.amount(), '100');
    expect(hintOf(field.currency())).toHaveTextContent(COST_PAIR);
    expect(submit()).toHaveAttribute('aria-disabled', 'true');
    await user.type(field.currency(), 'EUR');
    expect(hintOf(field.currency())).toBeEmptyDOMElement();
    expect(submit()).not.toHaveAttribute('aria-disabled');
    await user.clear(field.amount());
    expect(hintOf(field.amount())).toHaveTextContent(COST_PAIR);
    expect(submit()).toHaveAttribute('aria-disabled', 'true');
  });

  it('creates with one POST /v2 carrying an Idempotency-Key and navigates to the SKU page on 201', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '7');
    await user.click(submit());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(store.requests).toHaveLength(1);
    const req = store.requests[0]!;
    expect(req.method).toBe('POST');
    expect(new URL(req.url).pathname).toBe('/v2/inventory/DRS-1');
    expect(req.headers.get('Idempotency-Key')).toMatch(UUID);
    expect(await req.json()).toEqual({
      details: {
        name: 'Linen dress',
        description: 'A midi dress.',
        cost: { amount: 12900, currency: 'USD' },
        images: ['https://img.example/1.jpg', 'https://img.example/2.jpg'],
      },
      initialQuantity: 7,
    });
    expect(store.items.get('DRS-1')).toBe(7);
  });

  it('defaults the initial stock to 0 and omits cost when both fields are empty', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await user.type(field.skuId(), 'B-2');
    await user.type(field.name(), 'Belt');
    await user.click(submit());
    await waitFor(() => expect(store.requests).toHaveLength(1));
    expect(await store.requests[0]!.json()).toEqual({ details: { name: 'Belt', description: '', images: [] }, initialQuantity: 0 });
  });

  it('409: the server text verbatim, then a line linking to the existing SKU; the key is dropped', async () => {
    const user = userEvent.setup();
    store.seed({ 'DRS-1': 3 });
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.exists)).toBe(true);
    expect(alert).toHaveFocus();
    expect(screen.getByRole('link', { name: /open DRS-1/i })).toHaveAttribute('href', '#/sku/DRS-1');
    expect(window.location.hash).toBe('');
    expect(field.name()).toHaveValue('Linen dress');
    await user.click(submit());
    await waitFor(() => expect(store.requests).toHaveLength(2));
    expect(store.requests[1]!.headers.get('Idempotency-Key')).not.toBe(store.requests[0]!.headers.get('Idempotency-Key'));
  });

  it('400: the server text verbatim with the check-the-values line', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/v2/inventory/:skuId', () => new HttpResponse(TEXT.invalid, { status: 400, headers: { 'Content-Type': 'text/plain' } })));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.invalid)).toBe(true);
    expect(alert).toHaveTextContent(GUIDANCE.refused);
  });

  it('network failure: says a retry is safe and reuses the key', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/v2/inventory/:skuId', () => HttpResponse.error(), { once: true }));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(/network/i);
    expect(alert).toHaveTextContent(GUIDANCE.retrySafe);
    expect(store.requests).toHaveLength(1);
    await user.click(submit());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(store.requests).toHaveLength(2);
    expect(store.requests[1]!.headers.get('Idempotency-Key')).toBe(store.requests[0]!.headers.get('Idempotency-Key'));
  });

  it('in flight: aria-busy form, read-only fields, aria-disabled button; a double click sends one request', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    server.use(
      http.post('*/v2/inventory/:skuId', async () => {
        await new Promise<void>((r) => (release = r));
        return HttpResponse.json({ skuId: 'DRS-1', quantity: 0, details: { name: 'x' } }, { status: 201, headers: { ETag: '"1"' } });
      }),
    );
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.dblClick(submit());
    await waitFor(() => expect(screen.getByRole('form', { name: 'New SKU' })).toHaveAttribute('aria-busy', 'true'));
    expect(field.name()).toHaveAttribute('readonly');
    expect(field.images()).toHaveAttribute('readonly');
    expect(field.name()).not.toBeDisabled();
    const busy = screen.getByRole('button', { name: 'Sending…' });
    expect(busy).toHaveAttribute('aria-disabled', 'true');
    expect(busy).not.toBeDisabled();
    await user.keyboard('{Enter}');
    release();
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(store.requests).toHaveLength(1);
  });
});
