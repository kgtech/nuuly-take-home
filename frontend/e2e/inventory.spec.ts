import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

// Runs against the real service (API_URL, default :8080) through the Vite dev proxy (VITE_PORT, default 5173).
// The front end calls only /v2 (OD-7): every URL below is /v2, matched by exact pathname.
const sku = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

async function openSku(page: Page, skuId: string) {
  await page.goto(`/#/sku/${encodeURIComponent(skuId)}`);
  await expect(page.getByRole('heading', { level: 1, name: skuId })).toBeVisible();
}

const addForm = (page: Page) => page.getByRole('form', { name: 'Add stock' });
const buyForm = (page: Page) => page.getByRole('form', { name: 'Purchase' });
// The SKU page owns one outcome area for both forms (FE32).
const outcome = (page: Page) => page.getByRole('status');
const failure = (page: Page) => page.getByRole('alert');

async function addStock(page: Page, quantity: number) {
  await addForm(page).getByLabel('Quantity').fill(String(quantity));
  await addForm(page).getByRole('button', { name: 'Add stock' }).click();
  await expect(outcome(page)).toBeVisible();
}

test('add stock then purchase', async ({ page }) => {
  const skuId = sku('e2e-add');
  await openSku(page, skuId);
  await expect(failure(page)).toHaveText('SKU not found');

  // Unavailable buttons carry a visible reason and send nothing (FE31).
  const posts: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST') posts.push(r.url());
  });
  const add = addForm(page).getByRole('button', { name: 'Add stock' });
  await expect(add).toHaveAttribute('aria-disabled', 'true');
  await expect(addForm(page).getByText('Enter a whole number of at least 1.')).toBeVisible();
  // Playwright's actionability check treats aria-disabled as not enabled, so the click is forced: the point is
  // that the app itself ignores it.
  await add.click({ force: true });
  const buy = buyForm(page).getByRole('button', { name: 'Purchase' });
  await expect(buy).toHaveAttribute('aria-disabled', 'true');
  await expect(buyForm(page).getByText('Add stock first to create this SKU.')).toBeVisible();
  await buyForm(page).getByLabel('Quantity').fill('1');
  await buy.click({ force: true });
  expect(posts).toHaveLength(0);
  await buyForm(page).getByLabel('Quantity').fill('');

  await addStock(page, 5);
  await expect(outcome(page)).toContainText(`Added 5 to ${skuId}: now 5.`);
  await expect(page.getByTestId('quantity')).toHaveText('5');
  // The key sits behind a collapsed Request reference, not in the sentence (FE33).
  const reference = outcome(page).locator('details');
  await expect(reference).not.toHaveAttribute('open');
  await expect(reference.locator('summary')).toHaveText('Request reference');
  await expect(reference).toContainText(/Idempotency-Key [0-9a-f-]{36}/);
  await expect(outcome(page).locator('> div > div').first()).not.toContainText('Idempotency-Key');
  // The SKU now exists, so only the empty quantity keeps Purchase unavailable; a quantity releases it.
  await expect(buyForm(page).getByText('Add stock first to create this SKU.')).toHaveCount(0);
  await buyForm(page).getByLabel('Quantity').fill('2');
  await expect(buy).not.toHaveAttribute('aria-disabled', 'true');
  await buy.click();
  await expect(outcome(page)).toContainText(`Purchased 2 of ${skuId}: 3 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('3');
  await expect(page.getByRole('status')).toHaveCount(1);

  await buyForm(page).getByLabel('Quantity').fill('4');
  await buy.click();
  await expect(failure(page)).toContainText('Insufficient inventory');
  await expect(failure(page)).toContainText('Only 3 on hand now. Lower the quantity or add stock.');
  await expect(page.getByRole('status')).toHaveCount(0);

  await page.goto('/');
  await page.getByLabel('After SKU').fill(skuId.slice(0, -1));
  await page.getByRole('button', { name: 'Apply' }).click();
  const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: skuId }) });
  await expect(row).toBeVisible();
  await expect(row.getByRole('cell').nth(1)).toHaveText('—');
  await expect(row.getByRole('cell').nth(2)).toHaveText('Almost gone');
  await expect(row.getByRole('cell').nth(3)).toHaveText('3');
});

test('a double-submitted purchase changes stock once', async ({ page }) => {
  const skuId = sku('e2e-dbl');
  const purchasePath = `/v2/inventory/${encodeURIComponent(skuId)}/purchase`;
  await openSku(page, skuId);
  await addStock(page, 10);
  await expect(page.getByTestId('quantity')).toHaveText('10');

  const keys: (string | undefined)[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && new URL(r.url()).pathname === purchasePath) keys.push(r.headers()['idempotency-key']);
  });

  await buyForm(page).getByLabel('Quantity').fill('3');
  await buyForm(page).getByRole('button', { name: 'Purchase' }).dblclick();
  await expect(outcome(page)).toContainText(`Purchased 3 of ${skuId}: 7 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('7');

  await page.reload();
  await expect(page.getByTestId('quantity')).toHaveText('7');
  // The stock changed once: the service says 7, not 4.
  const read = await page.request.get(`/v2/inventory/${encodeURIComponent(skuId)}`);
  expect(read.status()).toBe(200);
  expect((await read.json()).quantity).toBe(7);
  expect(keys).toHaveLength(1);
  expect(keys[0]).toMatch(UUID_V4);
});

test('a retry after a network failure reuses the key and changes stock once', async ({ page }) => {
  const skuId = sku('e2e-retry');
  const purchasePath = `/v2/inventory/${encodeURIComponent(skuId)}/purchase`;
  await openSku(page, skuId);
  await addStock(page, 10);

  const keys: (string | undefined)[] = [];
  let failed = false;
  // The server processes the first purchase, but the browser sees a network error.
  await page.route((url) => url.pathname === purchasePath, async (route) => {
    keys.push(route.request().headers()['idempotency-key']);
    if (!failed) {
      failed = true;
      await route.fetch();
      await route.abort('connectionreset');
      return;
    }
    await route.continue();
  });

  await buyForm(page).getByLabel('Quantity').fill('4');
  const button = buyForm(page).getByRole('button', { name: 'Purchase' });
  await button.click();
  await expect(failure(page)).toContainText('Network error');
  await expect(failure(page)).toContainText('Sending again is safe: the same request will not be applied twice.');
  await expect(button).not.toHaveAttribute('aria-disabled', 'true');
  await expect(buyForm(page).getByLabel('Quantity')).not.toHaveAttribute('readonly');
  await button.click();
  await expect(outcome(page)).toContainText(`Purchased 4 of ${skuId}: 6 left.`);

  expect(keys).toHaveLength(2);
  expect(keys[0]).toMatch(UUID_V4);
  expect(keys[1]).toBe(keys[0]);
  await page.reload();
  await expect(page.getByTestId('quantity')).toHaveText('6');
  const read = await page.request.get(`/v2/inventory/${encodeURIComponent(skuId)}`);
  expect((await read.json()).quantity).toBe(6);
});

test('the main flows request only /v2 paths and static assets, never an unversioned /inventory path (OD-7)', async ({ page }) => {
  const skuId = sku('e2e-v2only');
  const paths: string[] = [];
  page.on('request', (r) => {
    const u = new URL(r.url());
    if (u.hostname === 'localhost') paths.push(u.pathname);
  });
  // List, SKU page, add (creating the SKU), purchase, a refused purchase, the Add stock page and Create.
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
  await openSku(page, skuId);
  await addStock(page, 5);
  await buyForm(page).getByLabel('Quantity').fill('2');
  await buyForm(page).getByRole('button', { name: 'Purchase' }).click();
  await expect(outcome(page)).toContainText(`Purchased 2 of ${skuId}: 3 left.`);
  await buyForm(page).getByLabel('Quantity').fill('9');
  await buyForm(page).getByRole('button', { name: 'Purchase' }).click();
  await expect(failure(page)).toContainText('Insufficient inventory');
  await page.goto('/#/add');
  await page.getByLabel('SKU ID').fill(`${skuId}-b`);
  await page.getByLabel('Quantity').fill('1');
  await page.getByRole('button', { name: 'Add stock' }).click();
  await expect(outcome(page)).toContainText('Added 1');
  await page.goto('/#/new');
  await page.getByLabel('SKU ID').fill(`${skuId}-c`);
  await page.getByLabel('Name', { exact: true }).fill('V2 only');
  await page.getByLabel(/^Initial stock/).fill('2');
  await page.getByRole('button', { name: 'Create SKU' }).click();
  await expect(page.getByTestId('quantity')).toHaveText('2');

  const api = paths.filter((p) => p.startsWith('/inventory'));
  expect(api).toEqual([]);
  const v2 = paths.filter((p) => p.startsWith('/v2/inventory'));
  expect(v2.length).toBeGreaterThan(5);
  expect(v2).toContain(`/v2/inventory/${skuId}/purchase`);
  expect(v2).toContain(`/v2/inventory/${skuId}-b`);
  expect(v2).toContain(`/v2/inventory/${skuId}-c`);
});

test('Find a SKU: Open is unavailable with a reason until the id is valid', async ({ page }) => {
  await page.goto('/');
  const open = page.getByRole('button', { name: 'Open' });
  const input = page.getByRole('form', { name: 'Find a SKU' }).getByLabel('SKU ID');
  await expect(open).toHaveAttribute('aria-disabled', 'true');
  await expect(page.getByText('Enter a SKU ID to open it.')).toBeVisible();
  await open.click({ force: true });
  await expect(page).toHaveURL(/\/#?\/?$/);
  await input.fill('bad id');
  await expect(page.getByText(/That isn't a valid SKU ID/)).toBeVisible();
  await expect(open).toHaveAttribute('aria-disabled', 'true');
  await input.press('Enter');
  await expect(page).toHaveURL(/\/#?\/?$/);
  await input.fill('ok-1');
  await expect(open).not.toHaveAttribute('aria-disabled', 'true');
  await input.press('Enter');
  await expect(page).toHaveURL(/#\/sku\/ok-1$/);
});

test.describe('accessibility and phone width', () => {
  const views: [string, (skuId: string) => string][] = [
    ['list', () => '/#/'],
    ['sku', (id) => `/#/sku/${encodeURIComponent(id)}`],
    ['add', () => '/#/add'],
  ];
  for (const [name, path] of views) {
    test(`${name} view has no axe violations and no horizontal overflow`, async ({ page }, info) => {
      const skuId = sku('e2e-a11y');
      if (name === 'sku') {
        await openSku(page, skuId);
        await addStock(page, 2);
      }
      await page.goto(path(skuId));
      await expect(page.getByRole('heading', { level: 1 })).toBeVisible();
      await expect(page.getByRole('status').or(page.getByRole('table')).or(page.getByRole('form')).first()).toBeVisible();
      const results = await new AxeBuilder({ page }).analyze();
      expect(results.violations).toEqual([]);
      const width = info.project.use.viewport?.width ?? 1280;
      const scrollWidth = await page.evaluate(() => document.documentElement.scrollWidth);
      expect(scrollWidth).toBeLessThanOrEqual(width);
      await page.screenshot({ path: `e2e/screenshots/${info.project.name}-${name}.png`, fullPage: true });
    });
  }
});
