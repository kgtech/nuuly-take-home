/** Mirrors the server's rules (G11, InventoryQuantity) for hints only; never blocks a request. */
export const SKU_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
export const SKU_ID_HINT =
  "1 to 64 letters, digits, '.', '_' or '-', starting with a letter or digit. Case-sensitive.";
export const QUANTITY_HINT = 'A whole number of at least 1.';
export const MAX_INT32 = 2_147_483_647;

export function skuIdHint(value: string): string | null {
  return value === '' || SKU_ID_PATTERN.test(value) ? null : SKU_ID_HINT;
}

export function quantityHint(value: string): string | null {
  if (value === '') return null;
  const n = Number(value);
  if (!Number.isInteger(n) || n < 1) return QUANTITY_HINT;
  if (n > MAX_INT32) return `At most ${MAX_INT32}.`;
  return null;
}

export const INVALID_REQUEST = 'Invalid request';
export const SKU_NOT_FOUND = 'SKU not found';

/** What the server is certain to answer for this input, or null when it might accept it. */
export function certainRejection(
  operation: 'add' | 'purchase',
  skuId: string,
  quantity: string,
): string | null {
  if (quantityHint(quantity) !== null || quantity === '') return INVALID_REQUEST;
  if (!SKU_ID_PATTERN.test(skuId)) return operation === 'add' ? INVALID_REQUEST : SKU_NOT_FOUND;
  return null;
}
