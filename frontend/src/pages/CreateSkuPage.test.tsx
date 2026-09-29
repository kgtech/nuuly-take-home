import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server, store, TEXT } from '../test/server';
import { CreateSkuPage } from './CreateSkuPage';
import { GUIDANCE } from '../components/Messages';
import { CONTROL_CHARS, COST_AMOUNT, COST_PAIR, IMAGE_URL_RULE, INITIAL_STOCK, SKU_ID_EMPTY, SKU_ID_INVALID } from '../validation';

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
    ['image URLs', 'images', 'https://ok.example/1\nnot a url', `Line 2: ${IMAGE_URL_RULE}`],
    ['image URL with a space (java.net.URI rejects it)', 'images', 'https://ok.example/a b.jpg', `Line 1: ${IMAGE_URL_RULE}`],
    ['name with a control character', 'name', 'Linen\u0007', CONTROL_CHARS],
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

  const puts = () => store.requests.filter((r) => r.method === 'PUT');
  const adds = () => store.requests.filter((r) => r.method === 'POST');
  const detailsBody = {
    name: 'Linen dress',
    description: 'A midi dress.',
    cost: { amount: 12900, currency: 'USD' },
    images: ['https://img.example/1.jpg', 'https://img.example/2.jpg'],
  };
  const retry = () => screen.getByRole('button', { name: 'Retry' });
  const editHref = '#/sku/DRS-1/edit';
  const text = (status: number, body: string) => new HttpResponse(body, { status, headers: { 'Content-Type': 'text/plain' } });

  it('step 1 is PUT .../details with If-None-Match * and no Idempotency-Key; 201 with stock 0 navigates and sends no add', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(store.requests).toHaveLength(1);
    const req = store.requests[0]!;
    expect(req.method).toBe('PUT');
    expect(new URL(req.url).pathname).toBe('/v2/inventory/DRS-1/details');
    expect(req.headers.get('If-None-Match')).toBe('*');
    expect(req.headers.has('If-Match')).toBe(false);
    expect(req.headers.has('Idempotency-Key')).toBe(false);
    expect(await req.json()).toEqual(detailsBody);
    expect(store.items.get('DRS-1')).toBe(0);
  });

  it('defaults the initial stock to 0 and omits cost when both fields are empty', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await user.type(field.skuId(), 'B-2');
    await user.type(field.name(), 'Belt');
    await user.click(submit());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/B-2'));
    expect(store.requests).toHaveLength(1);
    expect(await store.requests[0]!.json()).toEqual({ name: 'Belt', description: '', images: [] });
  });

  it('initial stock above 0: after the 201, one keyed add through POST /inventory, then navigates', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '7');
    await user.click(submit());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(store.requests.map((r) => r.method)).toEqual(['PUT', 'POST']);
    const [put, add] = store.requests as [Request, Request];
    expect(put.headers.has('Idempotency-Key')).toBe(false);
    expect(new URL(add.url).pathname).toBe('/inventory/DRS-1');
    expect(add.headers.get('Idempotency-Key')).toMatch(UUID);
    expect(await add.json()).toEqual({ quantity: 7 });
    expect(store.items.get('DRS-1')).toBe(7);
    expect(store.details.get('DRS-1')?.details.name).toBe('Linen dress');
  });

  it('412: the server text verbatim, its own line and a link to the SKU edit page; nothing else is sent', async () => {
    const user = userEvent.setup();
    store.seedDetails('DRS-1', 4, { name: 'Original', description: 'A', images: [] });
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '9');
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.changed)).toBe(true);
    expect(alert).toHaveTextContent('A SKU with this ID already exists. Open it to edit its details or add stock.');
    expect(alert).toHaveFocus();
    expect(alert.querySelector(`a[href="${editHref}"]`)).not.toBeNull();
    expect(window.location.hash).toBe('');
    expect(store.requests).toHaveLength(1);
    expect(store.requests[0]!.method).toBe('PUT');
    expect(store.items.get('DRS-1')).toBe(4);
    expect(store.details.get('DRS-1')?.details.name).toBe('Original');
    expect(field.name()).toHaveValue('Linen dress');
  });

  /** The service applies the create, then the browser sees a failure instead of the 201 (a lost response). */
  const appliedThen = (respond: () => Response) =>
    http.put(
      '*/v2/inventory/:skuId/details',
      ({ params }) => {
        store.items.set(String(params.skuId), 0);
        store.details.set(String(params.skuId), { details: detailsBody, version: 1 });
        return respond();
      },
      { once: true },
    );
  const LOST =
    'This SKU already exists. It was most likely created by your previous attempt, whose response was lost. Open it to check its details and add the initial stock.';

  it.each([
    ['a network error', () => HttpResponse.error()],
    ['a 502', () => text(502, 'Bad gateway')],
  ] as const)('a create applied server-side but answered with %s: the next click gets 412 and the page says the earlier attempt most likely created it', async (_l, respond) => {
    const user = userEvent.setup();
    server.use(appliedThen(respond));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '7');
    await user.click(submit());
    await screen.findByRole('alert');
    await user.click(submit());
    await waitFor(() => expect(puts()).toHaveLength(2));
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent(LOST));
    const alert = screen.getByRole('alert');
    expect(alert.textContent).not.toContain(TEXT.changed);
    expect(alert).not.toHaveTextContent('Open it to edit its details or add stock.');
    expect(alert.querySelector('a[href="#/sku/DRS-1"]')).not.toBeNull();
    expect(alert.querySelector(`a[href="${editHref}"]`)).toBeNull();
    expect(alert).toHaveFocus();
    expect(adds()).toHaveLength(0);
    expect(store.items.get('DRS-1')).toBe(0);
    expect(window.location.hash).toBe('');
  });

  it.each([
    ['a network error', () => HttpResponse.error(), /network/i],
    ['a 502', () => text(502, 'Bad gateway'), /bad gateway/i],
  ] as const)('a create PUT failing with %s says the SKU may or may not have been created, never that sending again is safe', async (_l, respond, shown) => {
    const user = userEvent.setup();
    server.use(http.put('*/v2/inventory/:skuId/details', respond, { once: true }));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(shown);
    expect(alert).toHaveTextContent(/may or may not have been created/i);
    expect(alert.textContent).not.toContain(GUIDANCE.retrySafe);
    expect(alert.textContent).not.toMatch(/sending again is safe/i);
  });

  it('a 412 with no earlier uncertain attempt keeps the server text, the exists line and the edit link', async () => {
    const user = userEvent.setup();
    store.seedDetails('DRS-1', 4, { name: 'Original', description: 'A', images: [] });
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.changed)).toBe(true);
    expect(alert).not.toHaveTextContent(/most likely created/);
    expect(alert.querySelector(`a[href="${editHref}"]`)).not.toBeNull();
  });

  it('focus stays in the form while the add is in flight and lands on the alert when it fails', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    server.use(
      http.post('*/inventory/:skuId', async () => {
        await new Promise<void>((r) => (release = r));
        return HttpResponse.error();
      }, { once: true }),
    );
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '7');
    await user.click(submit());
    await waitFor(() => expect(adds()).toHaveLength(1));
    const form = screen.getByRole('form', { name: 'New SKU' });
    expect(document.activeElement).not.toBe(document.body);
    expect(form).toContainElement(document.activeElement as HTMLElement);
    release();
    const alert = await screen.findByRole('alert');
    await waitFor(() => expect(alert).toHaveFocus());
    expect(document.activeElement).toBe(alert);
  });

  it('400 on the PUT: the server text verbatim with the check-the-values line', async () => {
    const user = userEvent.setup();
    server.use(http.put('*/v2/inventory/:skuId/details', () => text(400, TEXT.invalid)));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.invalid)).toBe(true);
    expect(alert).toHaveTextContent(GUIDANCE.refused);
    expect(adds()).toHaveLength(0);
  });

  it('network failure on the PUT: shows it, sends no key, and the next click sends the PUT again', async () => {
    const user = userEvent.setup();
    server.use(http.put('*/v2/inventory/:skuId/details', () => HttpResponse.error(), { once: true }));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.click(submit());
    expect(await screen.findByRole('alert')).toHaveTextContent(/network/i);
    await user.click(submit());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(puts()).toHaveLength(2);
    expect(puts().every((r) => !r.headers.has('Idempotency-Key'))).toBe(true);
  });

  it.each([
    ['a network error', () => HttpResponse.error()],
    ['a 502', () => text(502, 'Bad gateway')],
  ] as const)('the add fails with %s: server error, "exists at 0 stock", Retry sends only the add with the same key', async (_l, failure) => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', failure, { once: true }));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '7');
    await user.click(submit());
    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent(_l === 'a 502' ? 'Bad gateway' : /network/i);
    expect(alert).toHaveTextContent(/exists at 0 stock until the add succeeds/i);
    expect(window.location.hash).toBe('');
    expect(store.items.get('DRS-1')).toBe(0);
    expect(puts()).toHaveLength(1);
    expect(adds()).toHaveLength(1);

    await user.click(retry());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(puts()).toHaveLength(1);
    expect(adds()).toHaveLength(2);
    expect(adds()[0]!.headers.get('Idempotency-Key')).toMatch(UUID);
    expect(adds()[1]!.headers.get('Idempotency-Key')).toBe(adds()[0]!.headers.get('Idempotency-Key'));
    expect(store.items.get('DRS-1')).toBe(7);
  });

  it('a retried add whose first try was applied but not seen is replayed: stock is the initial quantity once', async () => {
    const user = userEvent.setup();
    server.use(
      http.post('*/inventory/:skuId', async ({ request, params }) => {
        const key = request.headers.get('Idempotency-Key')!;
        const body = (await request.json()) as { quantity: number };
        store.items.set(String(params.skuId), (store.items.get(String(params.skuId)) ?? 0) + body.quantity);
        store.keys.set(key, {
          hash: `add\n${String(params.skuId)}\n${body.quantity}`,
          status: 200,
          body: JSON.stringify({ skuId: String(params.skuId), quantity: body.quantity }),
          contentType: 'application/json',
        });
        return HttpResponse.error();
      }, { once: true }),
    );
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '5');
    await user.click(submit());
    await screen.findByRole('alert');
    await user.click(retry());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(store.items.get('DRS-1')).toBe(5);
    expect(puts()).toHaveLength(1);
  });

  it('after a non-retryable add failure the key is dropped: the next Retry sends a new key', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => text(400, TEXT.invalid), { once: true }));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '7');
    await user.click(submit());
    expect((await screen.findByRole('alert')).textContent!.startsWith(TEXT.invalid)).toBe(true);
    await user.click(retry());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/DRS-1'));
    expect(puts()).toHaveLength(1);
    expect(adds()).toHaveLength(2);
    expect(adds()[1]!.headers.get('Idempotency-Key')).not.toBe(adds()[0]!.headers.get('Idempotency-Key'));
  });

  it('after step 1 succeeds and the add fails the form is locked: SKU ID, details and initial stock are read-only', async () => {
    const user = userEvent.setup();
    server.use(http.post('*/inventory/:skuId', () => HttpResponse.error(), { once: true }));
    render(<CreateSkuPage />);
    await fillValid(user);
    await user.type(field.initial(), '7');
    await user.click(submit());
    await screen.findByRole('alert');
    for (const f of Object.values(field)) expect(f()).toHaveAttribute('readonly');
    await user.type(field.name(), 'x');
    expect(field.name()).toHaveValue('Linen dress');
    expect(field.name()).not.toBeDisabled();
    expect(puts()).toHaveLength(1);
  });

  it('in flight: aria-busy form, read-only fields, aria-disabled button; a double click sends one PUT', async () => {
    const user = userEvent.setup();
    let release: () => void = () => {};
    server.use(
      http.put('*/v2/inventory/:skuId/details', async () => {
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

  it('initial stock: text a number input cannot parse gets a "not a number" reason instead of sending 0 (F-fe-03)', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await fillValid(user);
    const initial = field.initial() as HTMLInputElement;
    await user.type(initial, '1');
    Object.defineProperty(initial, 'validity', { value: { badInput: true }, configurable: true });
    fireEvent.input(initial, { target: { value: '' } });
    expect(hintOf(initial)).toHaveTextContent('Enter a number.');
    expect(initial).toHaveAttribute('aria-invalid', 'true');
    expect(submit()).toHaveAttribute('aria-disabled', 'true');
    expect(submit().getAttribute('aria-describedby')!.split(' ')).toContain(hintOf(initial).id);
    await user.click(submit());
    expect(store.requests).toHaveLength(0);
    Object.defineProperty(initial, 'validity', { value: { badInput: false }, configurable: true });
    await user.type(initial, '3');
    expect(hintOf(initial)).toBeEmptyDOMElement();
    expect(submit()).not.toHaveAttribute('aria-disabled');
  });

  it.each([
    ['initial stock', 'initial'],
    ['cost amount', 'amount'],
  ] as const)('%s: badInput is read on input from an empty field, where React fires no change (R-01)', async (_l, key) => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await user.type(field.skuId(), 'A');
    await user.type(field.name(), 'A');
    if (key === 'amount') await user.type(field.currency(), 'USD');
    const input = field[key]() as HTMLInputElement;
    expect(input).toHaveValue(null);
    Object.defineProperty(input, 'validity', { value: { badInput: true }, configurable: true });
    fireEvent.input(input, { target: { value: '' } });
    expect(hintOf(input)).toHaveTextContent('Enter a number.');
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(submit()).toHaveAttribute('aria-disabled', 'true');
    await user.click(submit());
    await user.keyboard('{Enter}');
    expect(store.requests).toHaveLength(0);
  });

  it('a pasted name longer than 120 characters is kept as typed and refused, not truncated (F-09)', async () => {
    const user = userEvent.setup();
    render(<CreateSkuPage />);
    await user.click(field.name());
    await user.paste('n'.repeat(130));
    expect(field.name()).toHaveValue('n'.repeat(130));
    expect(field.name()).not.toHaveAttribute('maxlength');
    expect(field.description()).not.toHaveAttribute('maxlength');
    expect(hintOf(field.name())).toHaveTextContent('At most 120 characters.');
  });
});
