import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server, store, TEXT } from '../test/server';
import { EditSkuPage } from './EditSkuPage';
import { GUIDANCE } from '../components/Messages';
import { createClient, type SkuDetails } from '../api/client';
import { NO_VERSION } from './EditSkuPage';

const details: SkuDetails = {
  name: 'Linen dress',
  description: 'Midi',
  cost: { amount: 12900, currency: 'USD' },
  images: ['https://img.example/1.jpg'],
};
const name = () => screen.getByLabelText(/^name$/i);
const save = () => screen.getByRole('button', { name: 'Save details' });

describe('EditSkuPage (#/sku/:id/edit)', () => {
  it('loads GET /v2 and prefills every field', async () => {
    store.seedDetails('E-1', 2, details, 4);
    render(<EditSkuPage skuId="E-1" />);
    expect(screen.getByRole('status')).toHaveTextContent(/loading/i);
    expect(await screen.findByRole('heading', { level: 1, name: 'Edit details' })).toBeInTheDocument();
    expect(name()).toHaveValue('Linen dress');
    expect(screen.getByLabelText(/^description/i)).toHaveValue('Midi');
    expect(screen.getByLabelText(/cost amount/i)).toHaveValue(12900);
    expect(screen.getByLabelText(/^currency/i)).toHaveValue('USD');
    expect(screen.getByLabelText(/image urls/i)).toHaveValue('https://img.example/1.jpg');
    expect(screen.getByRole('link', { name: /back to sku/i })).toHaveAttribute('href', '#/sku/E-1');
  });

  it('sends PUT with If-Match from the ETag and returns to the SKU page on 200', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, details, 4);
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    await user.type(name(), ', sand');
    await user.clear(screen.getByLabelText(/cost amount/i));
    await user.clear(screen.getByLabelText(/^currency/i));
    await user.click(save());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/E-1'));
    const put = store.requests.find((r) => r.method === 'PUT')!;
    expect(new URL(put.url).pathname).toBe('/v2/inventory/E-1');
    expect(put.headers.get('If-Match')).toBe('"4"');
    expect(put.headers.has('Idempotency-Key')).toBe(false);
    expect(await put.json()).toEqual({ name: 'Linen dress, sand', description: 'Midi', images: ['https://img.example/1.jpg'] });
    expect(store.details.get('E-1')).toEqual({ details: { name: 'Linen dress, sand', description: 'Midi', images: ['https://img.example/1.jpg'] }, version: 5 });
  });

  it('a SKU without details offers to add them; PUT creates them with If-Match "0"', async () => {
    const user = userEvent.setup();
    store.seed({ plain: 1 });
    render(<EditSkuPage skuId="plain" />);
    expect(await screen.findByRole('heading', { level: 1, name: 'Add details' })).toBeInTheDocument();
    expect(name()).toHaveValue('');
    expect(save()).toHaveAttribute('aria-disabled', 'true');
    await user.type(name(), 'Plain tee');
    await user.click(save());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/plain'));
    const put = store.requests.find((r) => r.method === 'PUT')!;
    expect(put.headers.get('If-Match')).toBe('"0"');
    expect(store.details.get('plain')?.details.name).toBe('Plain tee');
  });

  it('412: the server text verbatim and a Reload that re-fetches the SKU and its ETag, after which a save succeeds', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, details, 1);
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    // Someone else saves meanwhile, through a real PUT (F-fe-06).
    const other = await createClient('http://localhost:3000').replaceSkuDetails('E-1', { ...details, name: 'Renamed elsewhere' }, '*');
    expect(other.ok && other.etag).toBe('"2"');
    await user.type(name(), ' v2');
    await user.click(save());
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.changed)).toBe(true);
    const pagePuts = () => store.requests.filter((r) => r.method === 'PUT' && r.headers.get('If-Match') !== '*');
    expect(pagePuts()[0]!.headers.get('If-Match')).toBe('"1"');
    expect(window.location.hash).toBe('');

    await user.click(screen.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(name()).toHaveValue('Renamed elsewhere'));
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(name()).toHaveFocus(); // F-07: focus does not fall to body when the Reload button unmounts
    await user.type(name(), ' v3');
    await user.click(save());
    await waitFor(() => expect(window.location.hash).toBe('#/sku/E-1'));
    expect(pagePuts()[1]!.headers.get('If-Match')).toBe('"2"');
    expect(store.details.get('E-1')?.details.name).toBe('Renamed elsewhere v3');
  });

  it('404 on load: the server text verbatim and a link to create the SKU', async () => {
    render(<EditSkuPage skuId="ghost" />);
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.notFound)).toBe(true);
    expect(screen.getByRole('link', { name: /create/i })).toHaveAttribute('href', '#/new');
    expect(screen.queryByRole('form')).not.toBeInTheDocument();
  });

  it('404 on save (deleted meanwhile is impossible, but the contract lists it): text verbatim and the create link', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, details);
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    server.use(http.put('*/v2/inventory/:skuId', () => new HttpResponse(TEXT.notFound, { status: 404, headers: { 'Content-Type': 'text/plain' } })));
    await user.click(save());
    const alert = await screen.findByRole('alert');
    expect(alert.textContent!.startsWith(TEXT.notFound)).toBe(true);
    expect(screen.getByRole('link', { name: /create/i })).toHaveAttribute('href', '#/new');
  });

  it('400 and 5xx on save: verbatim text with the guidance line; the fields keep their values', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, details);
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    server.use(http.put('*/v2/inventory/:skuId', () => new HttpResponse(TEXT.invalid, { status: 400, headers: { 'Content-Type': 'text/plain' } }), { once: true }));
    await user.type(name(), '!');
    await user.click(save());
    let alert = await screen.findByRole('alert');
    expect(alert.textContent).toBe(`${TEXT.invalid}${GUIDANCE.refused}`);
    expect(name()).toHaveValue('Linen dress!');
    server.use(http.put('*/v2/inventory/:skuId', () => new HttpResponse('Internal server error', { status: 500, headers: { 'Content-Type': 'text/plain' } }), { once: true }));
    await user.click(save());
    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('Internal server error'));
    alert = screen.getByRole('alert');
    expect(alert).toHaveTextContent(GUIDANCE.retrySafe);
  });

  it('a field reason makes Save unavailable and sends nothing', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, details);
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    await user.clear(name());
    const hint = document.getElementById(name().getAttribute('aria-describedby')!)!;
    expect(hint).toHaveTextContent('Enter a name.');
    expect(save()).toHaveAttribute('aria-disabled', 'true');
    expect(save().getAttribute('aria-describedby')!.split(' ')).toContain(hint.id);
    await user.click(save());
    expect(store.requests.filter((r) => r.method === 'PUT')).toHaveLength(0);
  });

  it('a stored cost above 2^53 is shown as too large to edit here, with Save unavailable (F-13)', async () => {
    store.seedDetails('E-1', 2, { name: 'Big', cost: { amount: 9007199254740992, currency: 'USD' } });
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    const amount = screen.getByLabelText(/cost amount/i);
    const hint = document.getElementById(amount.getAttribute('aria-describedby')!)!;
    expect(hint).toHaveTextContent("Amounts above 9,007,199,254,740,991 can't be entered or edited here.");
    expect(save()).toHaveAttribute('aria-disabled', 'true');
  });

  it('a stored IPv6 image URL loads with Save available (F-fe-01)', async () => {
    store.seedDetails('E-1', 2, { name: 'Six', images: ['https://[2001:db8::1]:8443/x.jpg', 'http://[::1]/a.png'] });
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    expect(screen.getByLabelText(/image urls/i)).toHaveValue('https://[2001:db8::1]:8443/x.jpg\nhttp://[::1]/a.png');
    expect(save()).not.toHaveAttribute('aria-disabled');
  });

  it('without an ETag on the read, Save is unavailable with a reason and no unconditional PUT is sent (F-fe-04)', async () => {
    const user = userEvent.setup();
    server.use(http.get('*/v2/inventory/:skuId', () => HttpResponse.json({ skuId: 'E-1', quantity: 2, details })));
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    expect(name()).toHaveValue('Linen dress');
    const reason = screen.getByText(NO_VERSION);
    expect(save()).toHaveAttribute('aria-disabled', 'true');
    expect(save().getAttribute('aria-describedby')!.split(' ')).toContain(reason.id);
    await user.click(save());
    await user.keyboard('{Enter}');
    expect(store.requests.filter((r) => r.method === 'PUT')).toHaveLength(0);
    expect(NO_VERSION).toBe('The service did not return a version; reload and try again.');
  });

  it('cost amount: text a number input cannot parse gets a "not a number" reason instead of silently omitting the cost (F-fe-03)', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, details);
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    const amount = screen.getByLabelText(/cost amount/i) as HTMLInputElement;
    const hint = document.getElementById(amount.getAttribute('aria-describedby')!)!;
    // jsdom never reports badInput; a browser does for "12-3" while showing it and reporting value "".
    Object.defineProperty(amount, 'validity', { value: { badInput: true }, configurable: true });
    fireEvent.input(amount, { target: { value: '' } });
    expect(hint).toHaveTextContent('Enter a number.');
    expect(amount).toHaveAttribute('aria-invalid', 'true');
    expect(save()).toHaveAttribute('aria-disabled', 'true');
    await user.click(save());
    expect(store.requests.filter((r) => r.method === 'PUT')).toHaveLength(0);
    Object.defineProperty(amount, 'validity', { value: { badInput: false }, configurable: true });
    await user.type(amount, '500');
    expect(hint).toBeEmptyDOMElement();
    expect(save()).not.toHaveAttribute('aria-disabled');
  });

  it('cost amount: badInput is read on input, which React fires even when the value stays "" (R-01)', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, { name: 'Plain' });
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    const amount = screen.getByLabelText(/cost amount/i) as HTMLInputElement;
    expect(amount).toHaveValue(null);
    const hint = document.getElementById(amount.getAttribute('aria-describedby')!)!;
    Object.defineProperty(amount, 'validity', { value: { badInput: true }, configurable: true });
    fireEvent.input(amount, { target: { value: '' } }); // "e5" typed from empty: the value stays ""
    expect(hint).toHaveTextContent('Enter a number.');
    expect(amount).toHaveAttribute('aria-invalid', 'true');
    expect(save()).toHaveAttribute('aria-disabled', 'true');
    await user.click(save());
    await user.keyboard('{Enter}');
    expect(store.requests.filter((r) => r.method === 'PUT')).toHaveLength(0);
  });

  it('Reload clears a stale "not a number" state so a valid reloaded amount is not flagged (R-02)', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, details, 1);
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    await createClient('http://localhost:3000').replaceSkuDetails('E-1', { ...details, cost: { amount: 777, currency: 'EUR' } }, '*');
    await user.click(save());
    await screen.findByRole('alert'); // 412
    // Before reloading, the amount field holds text a number input cannot parse.
    const amount = screen.getByLabelText(/cost amount/i) as HTMLInputElement;
    Object.defineProperty(amount, 'validity', { value: { badInput: true }, configurable: true });
    fireEvent.input(amount, { target: { value: '' } });
    expect(save()).toHaveAttribute('aria-disabled', 'true');
    Object.defineProperty(amount, 'validity', { value: { badInput: false }, configurable: true });
    await user.click(screen.getByRole('button', { name: 'Reload' }));
    await waitFor(() => expect(screen.getByLabelText(/cost amount/i)).toHaveValue(777));
    const hint = document.getElementById(screen.getByLabelText(/cost amount/i).getAttribute('aria-describedby')!)!;
    expect(hint).toBeEmptyDOMElement();
    expect(save()).not.toHaveAttribute('aria-disabled');
  });

  it('flags both cost fields when only one is filled (R-06)', async () => {
    const user = userEvent.setup();
    store.seedDetails('E-1', 2, { name: 'Plain' });
    render(<EditSkuPage skuId="E-1" />);
    await screen.findByRole('heading', { level: 1, name: 'Edit details' });
    const amount = screen.getByLabelText(/cost amount/i);
    const currency = screen.getByLabelText(/^currency/i);
    await user.type(currency, 'USD');
    expect(amount).toHaveAttribute('aria-invalid', 'true');
    expect(currency).not.toHaveAttribute('aria-invalid');
    await user.clear(currency);
    await user.type(amount, '100');
    expect(currency).toHaveAttribute('aria-invalid', 'true');
    expect(amount).not.toHaveAttribute('aria-invalid');
  });

  it('answers an id that fails G11 with "SKU not found" locally and the create link, sending nothing', async () => {
    render(<EditSkuPage skuId="bad id" />);
    expect(await screen.findByRole('alert')).toHaveTextContent(TEXT.notFound);
    expect(store.requests).toHaveLength(0);
    expect(screen.getByRole('link', { name: /create/i })).toHaveAttribute('href', '#/new');
  });
});
