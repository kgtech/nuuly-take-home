import { expect, test, type Page } from '@playwright/test';
import AxeBuilder from '@axe-core/playwright';

// Runs against the real service (API_URL, default :18080) through the Vite dev proxy (VITE_PORT, default 15173).
const sku = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;

async function openSku(page: Page, skuId: string) {
  await page.goto(`/#/sku/${encodeURIComponent(skuId)}`);
  await expect(page.getByRole('heading', { level: 1, name: skuId })).toBeVisible();
}

const addForm = (page: Page) => page.getByRole('form', { name: 'Add stock' });
const buyForm = (page: Page) => page.getByRole('form', { name: 'Purchase' });

async function addStock(page: Page, quantity: number) {
  await addForm(page).getByLabel('Quantity').fill(String(quantity));
  await addForm(page).getByRole('button', { name: 'Add stock' }).click();
  await expect(addForm(page).getByRole('status')).toBeVisible();
}

test('add stock then purchase', async ({ page }) => {
  const skuId = sku('e2e-add');
  await openSku(page, skuId);
  await expect(page.getByRole('alert')).toHaveText('SKU not found');

  await addStock(page, 5);
  await expect(addForm(page).getByRole('status')).toHaveText(`Added 5 to ${skuId}: now 5.`);
  await expect(page.getByTestId('quantity')).toHaveText('5');

  await buyForm(page).getByLabel('Quantity').fill('2');
  await buyForm(page).getByRole('button', { name: 'Purchase' }).click();
  await expect(buyForm(page).getByRole('status')).toHaveText(`Purchased 2 of ${skuId}: 3 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('3');

  await buyForm(page).getByLabel('Quantity').fill('4');
  await buyForm(page).getByRole('button', { name: 'Purchase' }).click();
  await expect(buyForm(page).getByRole('alert')).toHaveText('Insufficient inventory');

  await page.goto('/');
  await page.getByLabel('After SKU ID').fill(skuId.slice(0, -1));
  await page.getByRole('button', { name: 'Apply' }).click();
  const row = page.getByRole('row').filter({ has: page.getByRole('link', { name: skuId }) });
  await expect(row).toBeVisible();
  await expect(row.getByRole('cell').nth(1)).toHaveText('3');
});

test('a double-submitted purchase changes stock once', async ({ page }) => {
  const skuId = sku('e2e-dbl');
  const purchaseUrl = `/inventory/${encodeURIComponent(skuId)}/purchase`;
  await openSku(page, skuId);
  await addStock(page, 10);
  await expect(page.getByTestId('quantity')).toHaveText('10');

  const keys: (string | undefined)[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().endsWith(purchaseUrl)) keys.push(r.headers()['idempotency-key']);
  });

  await buyForm(page).getByLabel('Quantity').fill('3');
  await buyForm(page).getByRole('button', { name: 'Purchase' }).dblclick();
  await expect(buyForm(page).getByRole('status')).toHaveText(`Purchased 3 of ${skuId}: 7 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('7');

  await page.reload();
  await expect(page.getByTestId('quantity')).toHaveText('7');
  expect(keys).toHaveLength(1);
  expect(keys[0]).toMatch(UUID_V4);
});

test('a retry after a network failure reuses the key and changes stock once', async ({ page }) => {
  const skuId = sku('e2e-retry');
  const purchaseUrl = `/inventory/${encodeURIComponent(skuId)}/purchase`;
  await openSku(page, skuId);
  await addStock(page, 10);

  const keys: (string | undefined)[] = [];
  let failed = false;
  // The server processes the first purchase, but the browser sees a network error.
  await page.route(`**${purchaseUrl}`, async (route) => {
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
  await expect(buyForm(page).getByRole('alert')).toContainText('Network error');
  await expect(button).toBeEnabled();
  await button.click();
  await expect(buyForm(page).getByRole('status')).toHaveText(`Purchased 4 of ${skuId}: 6 left.`);

  expect(keys).toHaveLength(2);
  expect(keys[0]).toMatch(UUID_V4);
  expect(keys[1]).toBe(keys[0]);
  await page.reload();
  await expect(page.getByTestId('quantity')).toHaveText('6');
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
