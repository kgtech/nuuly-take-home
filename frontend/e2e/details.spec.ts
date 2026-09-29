import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';
import { randomUUID } from 'node:crypto';

// v2 details flows against the real service through the Vite dev proxy (only /v2 is proxied, OD-7).
const sku = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const IMAGE = 'https://example.com/e2e/dress.jpg';

async function fillDetails(page: Page, name: string) {
  await page.getByLabel('Name', { exact: true }).fill(name);
  await page.getByLabel(/^Description/).fill('A midi dress in sand.');
  await page.getByLabel(/^Cost amount/).fill('12900');
  await page.getByLabel(/^Currency/).fill('USD');
  await page.getByLabel(/^Image URLs/).fill(IMAGE);
}

async function createSku(page: Page, skuId: string, name: string, initial: number) {
  await page.goto('/#/new');
  await expect(page.getByRole('heading', { level: 1, name: 'New SKU' })).toBeVisible();
  await page.getByLabel('SKU ID').fill(skuId);
  await fillDetails(page, name);
  await page.getByLabel(/^Initial stock/).fill(String(initial));
  await page.getByRole('button', { name: 'Create SKU' }).click();
  await expect(page).toHaveURL(new RegExp(`#/sku/${encodeURIComponent(skuId)}$`));
  await expect(page.getByRole('heading', { level: 1, name: skuId })).toBeVisible();
}

test('create with details, edit them, then purchase', async ({ page }) => {
  const skuId = sku('e2e-v2');
  const detailsPath = `/v2/inventory/${encodeURIComponent(skuId)}/details`;
  const addPath = `/v2/inventory/${encodeURIComponent(skuId)}`;
  const seen: { method: string; path: string; headers: Record<string, string>; body: unknown }[] = [];
  page.on('request', (r) => {
    if (r.method() === 'GET') return;
    seen.push({ method: r.method(), path: new URL(r.url()).pathname, headers: r.headers(), body: r.postDataJSON() });
  });

  await createSku(page, skuId, 'Linen dress', 5);
  // Step 1: PUT details with If-None-Match: * and no Idempotency-Key; step 2: one keyed add. Nothing else.
  const creates = seen.filter((r) => r.method === 'PUT' && r.path === detailsPath);
  expect(creates).toHaveLength(1);
  expect(creates[0]?.headers['if-none-match']).toBe('*');
  expect(creates[0]?.headers['if-match']).toBeUndefined();
  expect(creates[0]?.headers['idempotency-key']).toBeUndefined();
  expect(creates[0]?.body).toEqual({
    name: 'Linen dress',
    description: 'A midi dress in sand.',
    cost: { amount: 12900, currency: 'USD' },
    images: [IMAGE],
  });
  const adds = seen.filter((r) => r.method === 'POST' && r.path === addPath);
  expect(adds).toHaveLength(1);
  expect(adds[0]?.headers['idempotency-key']).toMatch(UUID_V4);
  expect(adds[0]?.body).toEqual({ quantity: 5 });
  expect(seen.map((r) => `${r.method} ${r.path}`)).toEqual([`PUT ${detailsPath}`, `POST ${addPath}`]);

  // The SKU page shows the details and the initial quantity.
  await expect(page.getByRole('heading', { level: 2, name: 'Linen dress' })).toBeVisible();
  await expect(page.getByText('A midi dress in sand.', { exact: true })).toBeVisible();
  await expect(page.getByTestId('cost')).toHaveText('$129.00');
  await expect(page.getByTestId('quantity')).toHaveText('5');
  await expect(page.getByText('Available', { exact: true })).toBeVisible();
  // example.com serves no image: the thumbnail falls back and keeps the name.
  await expect(page.getByRole('img', { name: /Linen dress/ })).toBeVisible();

  // Edit: PUT with If-Match from the ETag, then back to the SKU page with the new details.
  const puts: { ifMatch: string | undefined; ifNoneMatch: string | undefined; body: unknown }[] = [];
  page.on('request', (r) => {
    if (r.method() === 'PUT' && new URL(r.url()).pathname === detailsPath) {
      puts.push({ ifMatch: r.headers()['if-match'], ifNoneMatch: r.headers()['if-none-match'], body: r.postDataJSON() });
    }
  });
  await page.getByRole('link', { name: 'Edit details' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Edit details' })).toBeVisible();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Linen dress');
  await page.getByLabel('Name', { exact: true }).fill('Linen dress, sand');
  await page.getByLabel(/^Cost amount/).fill('9900');
  await page.getByRole('button', { name: 'Save details' }).click();
  await expect(page).toHaveURL(new RegExp(`#/sku/${encodeURIComponent(skuId)}$`));
  await expect(page.getByRole('heading', { level: 2, name: 'Linen dress, sand' })).toBeVisible();
  await expect(page.getByTestId('cost')).toHaveText('$99.00');
  expect(puts).toHaveLength(1);
  expect(puts[0]?.ifMatch).toMatch(/^"\d+"$/);
  expect(puts[0]?.ifNoneMatch).toBeUndefined();
  expect(puts[0]?.body).toEqual({
    name: 'Linen dress, sand',
    description: 'A midi dress in sand.',
    cost: { amount: 9900, currency: 'USD' },
    images: [IMAGE],
  });

  // Purchase goes through /v2 and the details stay on screen (the page shows the write response).
  const buyForm = page.getByRole('form', { name: 'Purchase' });
  await buyForm.getByLabel('Quantity').fill('2');
  await buyForm.getByRole('button', { name: 'Purchase' }).click();
  await expect(page.getByRole('status')).toContainText(`Purchased 2 of ${skuId}: 3 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('3');
  await expect(page.getByRole('heading', { level: 2, name: 'Linen dress, sand' })).toBeVisible();
  expect(seen.filter((r) => r.method === 'POST' && r.path === `${addPath}/purchase`)).toHaveLength(1);

  // The list shows the name.
  await page.goto('/');
  await page.getByLabel('After SKU').fill(skuId.slice(0, -1));
  await page.getByRole('button', { name: 'Apply' }).click();
  const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: skuId }) });
  await expect(row.getByRole('cell').nth(1)).toHaveText('Linen dress, sand');
  await expect(row.getByRole('cell').nth(3)).toHaveText('3');
});

test('a double-clicked Create sends one PUT and one keyed add and creates one SKU', async ({ page }) => {
  const skuId = sku('e2e-dblcreate');
  const detailsPath = `/v2/inventory/${encodeURIComponent(skuId)}/details`;
  const addPath = `/v2/inventory/${encodeURIComponent(skuId)}`;
  const puts: (string | undefined)[] = [];
  const keys: (string | undefined)[] = [];
  page.on('request', (r) => {
    const path = new URL(r.url()).pathname;
    if (r.method() === 'PUT' && path === detailsPath) puts.push(r.headers()['idempotency-key']);
    if (r.method() === 'POST' && path === addPath) keys.push(r.headers()['idempotency-key']);
  });
  await page.goto('/#/new');
  await page.getByLabel('SKU ID').fill(skuId);
  await fillDetails(page, 'Double');
  await page.getByLabel(/^Initial stock/).fill('2');
  await page.getByRole('button', { name: 'Create SKU' }).dblclick();
  await expect(page).toHaveURL(new RegExp(`#/sku/${encodeURIComponent(skuId)}$`));
  await expect(page.getByTestId('quantity')).toHaveText('2');
  expect(puts).toEqual([undefined]);
  expect(keys).toHaveLength(1);
  expect(keys[0]).toMatch(UUID_V4);
  const read = await page.request.get(`/v2/inventory/${encodeURIComponent(skuId)}`);
  expect(read.status()).toBe(200);
  expect((await read.json()).quantity).toBe(2);
});

test('an add retried after a network failure sends the same key, no second PUT, and adds the initial stock once', async ({ page }) => {
  const skuId = sku('e2e-retrycreate');
  const detailsPath = `/v2/inventory/${encodeURIComponent(skuId)}/details`;
  const addPath = `/v2/inventory/${encodeURIComponent(skuId)}`;
  const keys: (string | undefined)[] = [];
  let putCount = 0;
  let failed = false;
  page.on('request', (r) => {
    if (r.method() === 'PUT' && new URL(r.url()).pathname === detailsPath) putCount += 1;
  });
  // The server processes the first add, but the browser sees a network error.
  await page.route((url) => url.pathname === addPath, async (route) => {
    if (route.request().method() !== 'POST') return route.continue();
    keys.push(route.request().headers()['idempotency-key']);
    if (!failed) {
      failed = true;
      await route.fetch();
      await route.abort('connectionreset');
      return;
    }
    await route.continue();
  });
  await page.goto('/#/new');
  await page.getByLabel('SKU ID').fill(skuId);
  await fillDetails(page, 'Retry');
  await page.getByLabel(/^Initial stock/).fill('3');
  await page.getByRole('button', { name: 'Create SKU' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).toContainText('Network error');
  await expect(alert).toContainText('exists at 0 stock');
  await page.getByRole('button', { name: 'Retry' }).click();
  await expect(page).toHaveURL(new RegExp(`#/sku/${encodeURIComponent(skuId)}$`));
  await expect(page.getByTestId('quantity')).toHaveText('3');
  expect(putCount).toBe(1);
  expect(keys).toHaveLength(2);
  expect(keys[0]).toMatch(UUID_V4);
  expect(keys[1]).toBe(keys[0]);
  const read = await page.request.get(`/v2/inventory/${encodeURIComponent(skuId)}`);
  expect((await read.json()).quantity).toBe(3);
});

test('a create whose response is lost: the next click says the earlier attempt most likely created it and sends no add', async ({ page }) => {
  const skuId = sku('e2e-lostcreate');
  const id = encodeURIComponent(skuId);
  const detailsPath = `/v2/inventory/${id}/details`;
  let putCount = 0;
  const adds: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === `/v2/inventory/${id}`) adds.push(r.url());
  });
  // The service applies the first create, but the browser sees a network error instead of the 201.
  await page.route((url) => url.pathname === detailsPath, async (route) => {
    putCount += 1;
    if (putCount === 1) {
      await route.fetch();
      await route.abort('connectionreset');
      return;
    }
    await route.continue();
  });
  await page.goto('/#/new');
  await page.getByLabel('SKU ID').fill(skuId);
  await fillDetails(page, 'Lost response');
  await page.getByLabel(/^Initial stock/).fill('6');
  await page.getByRole('button', { name: 'Create SKU' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).toContainText('Network error');
  await expect(alert).not.toContainText('Sending again is safe');
  await page.getByRole('button', { name: 'Create SKU' }).click();
  await expect(alert).toContainText(
    'This SKU already exists. It was most likely created by your previous attempt, whose response was lost. Open it to check its details and add the initial stock.',
  );
  await expect(alert).not.toContainText('Details changed since you read them');
  await expect(alert.locator(`a[href="#/sku/${id}"]`)).toBeVisible();
  await expect(page).toHaveURL(/#\/new$/);
  expect(putCount).toBe(2);
  expect(adds).toEqual([]);

  const read = await page.request.get(`/v2/inventory/${id}`);
  expect(read.status()).toBe(200);
  const now = await read.json();
  expect(now.quantity).toBe(0);
  expect(now.details.name).toBe('Lost response');
  expect(now.details.description).toBe('A midi dress in sand.');
});

test('creating an existing SKU changes nothing: the 412 text and a link to its edit page', async ({ page }) => {
  const skuId = sku('e2e-412create');
  const id = encodeURIComponent(skuId);
  const original = { name: 'Original A', description: 'First description', images: [] };
  const made = await page.request.put(`/v2/inventory/${id}/details`, {
    headers: { 'If-None-Match': '*' },
    data: original,
  });
  expect(made.status()).toBe(201);
  const stocked = await page.request.post(`/v2/inventory/${id}`, {
    headers: { 'Idempotency-Key': randomUUID() },
    data: { quantity: 4 },
  });
  expect(stocked.status()).toBe(200);

  await page.goto('/#/new');
  await page.getByLabel('SKU ID').fill(skuId);
  await fillDetails(page, 'Second');
  await page.getByLabel(/^Initial stock/).fill('9');
  await page.getByRole('button', { name: 'Create SKU' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).not.toContainText('Details changed since you read them');
  await expect(alert).toContainText('A SKU with this ID already exists. Open it to edit its details or add stock.');
  await expect(alert.locator(`a[href="#/sku/${id}/edit"]`)).toBeVisible();
  await expect(page).toHaveURL(/#\/new$/);

  const read = await page.request.get(`/v2/inventory/${id}`);
  expect(read.status()).toBe(200);
  const now = await read.json();
  expect(now.quantity).toBe(4);
  expect(now.details).toEqual(original);
});

test('a stale edit gets 412 and Reload shows the current details', async ({ page }) => {
  const skuId = sku('e2e-412');
  await createSku(page, skuId, 'Original', 1);
  await page.getByRole('link', { name: 'Edit details' }).click();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Original');

  // Another client replaces the details while this page holds the old ETag.
  const other = await page.request.put(`/v2/inventory/${encodeURIComponent(skuId)}/details`, {
    headers: { 'Content-Type': 'application/json', 'If-Match': '*' },
    data: { name: 'Changed elsewhere', description: '', images: [] },
  });
  expect(other.status()).toBe(200);

  await page.getByLabel('Name', { exact: true }).fill('My edit');
  await page.getByRole('button', { name: 'Save details' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).toContainText('Details changed since you read them');
  await alert.getByRole('button', { name: 'Reload' }).click();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Changed elsewhere');
  await page.getByLabel('Name', { exact: true }).fill('Changed elsewhere, then here');
  await page.getByRole('button', { name: 'Save details' }).click();
  await expect(page.getByRole('heading', { level: 2, name: 'Changed elsewhere, then here' })).toBeVisible();
});

test('a SKU created by add stock has no details and offers to add them', async ({ page }) => {
  const skuId = sku('e2e-nodetails');
  await page.goto(`/#/sku/${encodeURIComponent(skuId)}`);
  const addForm = page.getByRole('form', { name: 'Add stock' });
  await addForm.getByLabel('Quantity').fill('2');
  await addForm.getByRole('button', { name: 'Add stock' }).click();
  await expect(page.getByRole('status')).toBeVisible();
  await page.reload();
  await expect(page.getByText('No details yet', { exact: true })).toBeVisible();
  await page.getByRole('link', { name: 'Add details' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Add details' })).toBeVisible();
  await page.getByLabel('Name', { exact: true }).fill('Plain tee');
  await page.getByRole('button', { name: 'Save details' }).click();
  await expect(page.getByRole('heading', { level: 2, name: 'Plain tee' })).toBeVisible();
  await expect(page.getByTestId('quantity')).toHaveText('2');
});

test.describe('accessibility and phone width (v2 views)', () => {
  for (const name of ['new', 'edit'] as const) {
    test(`${name} view has no axe violations and no horizontal overflow`, async ({ page }, info) => {
      const skuId = sku('e2e-a11y-v2');
      if (name === 'edit') {
        await createSku(page, skuId, 'Axe check', 1);
        await page.goto(`/#/sku/${encodeURIComponent(skuId)}/edit`);
        await expect(page.getByRole('heading', { level: 1, name: 'Edit details' })).toBeVisible();
      } else {
        await page.goto('/#/new');
        await expect(page.getByRole('heading', { level: 1, name: 'New SKU' })).toBeVisible();
      }
      await expect(page.getByRole('form')).toBeVisible();
      const results = await new AxeBuilder({ page }).analyze();
      expect(results.violations).toEqual([]);
      const width = info.project.use.viewport?.width ?? 1280;
      const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      expect(scrollWidth).toBeLessThanOrEqual(width);
      await page.screenshot({ path: `e2e/screenshots/${info.project.name}-${name}.png`, fullPage: true });
    });
  }
});

test('a 118-character unbroken name and a 64-character SKU id never scroll sideways at 375 and 1280 px on the SKU, Edit and list pages (M-03)', async ({ page }) => {
  const stamp = `${Date.now()}${Math.floor(Math.random() * 1e6)}`;
  const skuId = `L${stamp}`.padEnd(64, 'X');
  expect(skuId).toHaveLength(64);
  const name = 'N'.repeat(118);
  const id = encodeURIComponent(skuId);
  const made = await page.request.put(`/v2/inventory/${id}/details`, {
    headers: { 'If-None-Match': '*' },
    data: { name, description: 'D'.repeat(300), images: [] },
  });
  expect(made.status()).toBe(201);
  const stocked = await page.request.post(`/v2/inventory/${id}`, {
    headers: { 'Idempotency-Key': randomUUID() },
    data: { quantity: 3 },
  });
  expect(stocked.status()).toBe(200);

  for (const width of [375, 1280]) {
    await page.setViewportSize({ width, height: 800 });
    const overflow = async (label: string) => {
      const m = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, inner: window.innerWidth }));
      expect(m.scroll, `${label} at ${width}px`).toBeLessThanOrEqual(m.inner);
    };
    await page.goto(`/#/sku/${id}`);
    await expect(page.getByRole('heading', { level: 2, name })).toBeVisible();
    await overflow('SKU page');
    await page.goto(`/#/sku/${id}/edit`);
    await expect(page.getByRole('heading', { level: 1, name: 'Edit details' })).toBeVisible();
    await expect(page.getByLabel('Name', { exact: true })).toHaveValue(name);
    await overflow('Edit page');
    await page.goto('/#/');
    await page.getByLabel('After SKU').fill(skuId.slice(0, -1));
    await page.getByRole('button', { name: 'Apply' }).click();
    await expect(page.getByRole('row').filter({ has: page.getByRole('link', { name: skuId }) })).toBeVisible();
    await overflow('list page');
  }
});
