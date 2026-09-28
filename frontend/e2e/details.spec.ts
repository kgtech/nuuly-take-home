import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

// v2 details flows against the real service through the Vite dev proxy (/v2 is proxied like /inventory).
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
  const createUrl = `/v2/inventory/${encodeURIComponent(skuId)}`;
  const posts: { url: string; key: string | undefined; body: unknown }[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST') posts.push({ url: r.url(), key: r.headers()['idempotency-key'], body: r.postDataJSON() });
  });

  await createSku(page, skuId, 'Linen dress', 5);
  // One POST /v2 with an Idempotency-Key and the whole request.
  const creates = posts.filter((p) => p.url.endsWith(createUrl));
  expect(creates).toHaveLength(1);
  expect(creates[0]?.key).toMatch(UUID_V4);
  expect(creates[0]?.body).toEqual({
    details: { name: 'Linen dress', description: 'A midi dress in sand.', cost: { amount: 12900, currency: 'USD' }, images: [IMAGE] },
    initialQuantity: 5,
  });

  // The SKU page shows the details and the initial quantity.
  await expect(page.getByRole('heading', { level: 2, name: 'Linen dress' })).toBeVisible();
  await expect(page.getByText('A midi dress in sand.')).toBeVisible();
  await expect(page.getByTestId('cost')).toHaveText('$129.00');
  await expect(page.getByTestId('quantity')).toHaveText('5');
  await expect(page.getByText('Available')).toBeVisible();
  // example.com serves no image: the thumbnail falls back and keeps the name.
  await expect(page.getByRole('img', { name: /Linen dress/ })).toBeVisible();

  // Edit: PUT with If-Match from the ETag, then back to the SKU page with the new details.
  const puts: { ifMatch: string | undefined; body: unknown }[] = [];
  page.on('request', (r) => {
    if (r.method() === 'PUT') puts.push({ ifMatch: r.headers()['if-match'], body: r.postDataJSON() });
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
  expect(puts[0]?.body).toEqual({
    name: 'Linen dress, sand',
    description: 'A midi dress in sand.',
    cost: { amount: 9900, currency: 'USD' },
    images: [IMAGE],
  });

  // Purchase still goes through v1 and the details stay on screen.
  const buyForm = page.getByRole('form', { name: 'Purchase' });
  await buyForm.getByLabel('Quantity').fill('2');
  await buyForm.getByRole('button', { name: 'Purchase' }).click();
  await expect(page.getByRole('status')).toContainText(`Purchased 2 of ${skuId}: 3 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('3');
  await expect(page.getByRole('heading', { level: 2, name: 'Linen dress, sand' })).toBeVisible();
  expect(posts.filter((p) => p.url.endsWith('/purchase'))).toHaveLength(1);

  // The list shows the name.
  await page.goto('/');
  await page.getByLabel('After SKU').fill(skuId.slice(0, -1));
  await page.getByRole('button', { name: 'Apply' }).click();
  const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: skuId }) });
  await expect(row.getByRole('cell').nth(1)).toHaveText('Linen dress, sand');
  await expect(row.getByRole('cell').nth(3)).toHaveText('3');
});

test('a double-clicked Create sends one POST with one key and creates one SKU', async ({ page }) => {
  const skuId = sku('e2e-dblcreate');
  const createUrl = `/v2/inventory/${encodeURIComponent(skuId)}`;
  const keys: (string | undefined)[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().endsWith(createUrl)) keys.push(r.headers()['idempotency-key']);
  });
  await page.goto('/#/new');
  await page.getByLabel('SKU ID').fill(skuId);
  await fillDetails(page, 'Double');
  await page.getByLabel(/^Initial stock/).fill('2');
  await page.getByRole('button', { name: 'Create SKU' }).dblclick();
  await expect(page).toHaveURL(new RegExp(`#/sku/${encodeURIComponent(skuId)}$`));
  await expect(page.getByTestId('quantity')).toHaveText('2');
  expect(keys).toHaveLength(1);
  expect(keys[0]).toMatch(UUID_V4);
  const read = await page.request.get(createUrl);
  expect(read.status()).toBe(200);
  expect((await read.json()).quantity).toBe(2);
});

test('a create retried after a network failure reuses the key and creates one SKU with its stock once', async ({ page }) => {
  const skuId = sku('e2e-retrycreate');
  const createUrl = `/v2/inventory/${encodeURIComponent(skuId)}`;
  const keys: (string | undefined)[] = [];
  let failed = false;
  // The server processes the first create, but the browser sees a network error.
  await page.route(`**${createUrl}`, async (route) => {
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
  const create = page.getByRole('button', { name: 'Create SKU' });
  await create.click();
  await expect(page.getByRole('alert')).toContainText('Network error');
  await expect(page.getByRole('alert')).toContainText('Sending again is safe');
  await create.click();
  await expect(page).toHaveURL(new RegExp(`#/sku/${encodeURIComponent(skuId)}$`));
  await expect(page.getByTestId('quantity')).toHaveText('3');
  expect(keys).toHaveLength(2);
  expect(keys[0]).toMatch(UUID_V4);
  expect(keys[1]).toBe(keys[0]);
  const read = await page.request.get(createUrl);
  expect((await read.json()).quantity).toBe(3);
});

test('creating an existing SKU shows the 409 text and a link to it', async ({ page }) => {
  const skuId = sku('e2e-409');
  await createSku(page, skuId, 'First', 0);
  await page.goto('/#/new');
  await page.getByLabel('SKU ID').fill(skuId);
  await fillDetails(page, 'Second');
  await page.getByRole('button', { name: 'Create SKU' }).click();
  const alert = page.getByRole('alert');
  await expect(alert).toContainText('SKU already exists');
  await expect(alert.getByRole('link', { name: `Open ${skuId}` })).toHaveAttribute('href', `#/sku/${encodeURIComponent(skuId)}`);
  await expect(page).toHaveURL(/#\/new$/);
});

test('a stale edit gets 412 and Reload shows the current details', async ({ page }) => {
  const skuId = sku('e2e-412');
  await createSku(page, skuId, 'Original', 1);
  await page.getByRole('link', { name: 'Edit details' }).click();
  await expect(page.getByLabel('Name', { exact: true })).toHaveValue('Original');

  // Another client replaces the details while this page holds the old ETag.
  const other = await page.request.put(`/v2/inventory/${encodeURIComponent(skuId)}`, {
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
  await expect(page.getByText('No details yet')).toBeVisible();
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
