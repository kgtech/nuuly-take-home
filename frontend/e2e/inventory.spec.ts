import { expect, test, type Page } from '@playwright/test';

// Runs against the real service on :8080 through the Vite dev proxy.
const sku = (prefix: string) => `${prefix}-${Date.now()}-${Math.floor(Math.random() * 1e6)}`;

async function openSku(page: Page, skuId: string) {
  await page.goto(`/#/sku/${encodeURIComponent(skuId)}`);
  await expect(page.getByRole('heading', { level: 1, name: skuId })).toBeVisible();
}

test('add stock then purchase', async ({ page }) => {
  const skuId = sku('e2e-add');
  await openSku(page, skuId);
  await expect(page.getByRole('alert')).toHaveText('SKU not found');

  const add = page.getByRole('form').filter({ has: page.getByRole('button', { name: 'Add stock' }) });
  await add.getByLabel('Quantity').fill('5');
  await add.getByRole('button', { name: 'Add stock' }).click();
  await expect(add.getByRole('status')).toHaveText(`Added 5 to ${skuId}: now 5.`);
  await expect(page.getByTestId('quantity')).toHaveText('5');

  const buy = page.getByRole('form').filter({ has: page.getByRole('button', { name: 'Purchase' }) });
  await buy.getByLabel('Quantity').fill('2');
  await buy.getByRole('button', { name: 'Purchase' }).click();
  await expect(buy.getByRole('status')).toHaveText(`Purchased 2 of ${skuId}: 3 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('3');

  await buy.getByLabel('Quantity').fill('4');
  await buy.getByRole('button', { name: 'Purchase' }).click();
  await expect(buy.getByRole('alert')).toHaveText('Insufficient inventory');

  await page.goto('/');
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Quantity' })).toBeVisible();
});

test('a double-submitted purchase changes stock once', async ({ page }) => {
  const skuId = sku('e2e-dbl');
  await openSku(page, skuId);
  const add = page.getByRole('form').filter({ has: page.getByRole('button', { name: 'Add stock' }) });
  await add.getByLabel('Quantity').fill('10');
  await add.getByRole('button', { name: 'Add stock' }).click();
  await expect(page.getByTestId('quantity')).toHaveText('10');

  const purchases: string[] = [];
  page.on('request', (r) => {
    if (r.method() === 'POST' && r.url().endsWith(`/inventory/${skuId}/purchase`)) purchases.push(r.url());
  });

  const buy = page.getByRole('form').filter({ has: page.getByRole('button', { name: 'Purchase' }) });
  await buy.getByLabel('Quantity').fill('3');
  await buy.getByRole('button', { name: 'Purchase' }).dblclick();
  await expect(buy.getByRole('status')).toHaveText(`Purchased 3 of ${skuId}: 7 left.`);
  await expect(page.getByTestId('quantity')).toHaveText('7');

  await page.reload();
  await expect(page.getByTestId('quantity')).toHaveText('7');
  expect(purchases.length).toBe(1);
});
