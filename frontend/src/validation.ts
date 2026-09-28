/**
 * Mirrors the server's rules (G11, InventoryQuantity, R4, DESIGN-V2 §8) as field-specific
 * reasons. A reason makes the submit button unavailable and is shown under the field; it is
 * never the server's text (FE10 as amended by FE30). Only input the server is certain to
 * reject gets a reason, so nothing the server might accept is ever blocked.
 */
export const SKU_ID_PATTERN = /^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$/;
export const SKU_ID_FORMAT = "Use 1 to 64 letters, digits, '.', '_' or '-', starting with a letter or digit.";
export const SKU_ID_INVALID = `That isn't a valid SKU ID. ${SKU_ID_FORMAT}`;
export const SKU_ID_EMPTY = 'Enter a SKU ID.';
export const SKU_ID_EMPTY_TO_OPEN = 'Enter a SKU ID to open it.';
export const QUANTITY_REASON = 'Enter a whole number of at least 1.';
export const MAX_INT32 = 2_147_483_647;
export const MAX_PAGE = 250;
export const LIMIT_REASON = `Outside 1–${MAX_PAGE}. The service will use ${MAX_PAGE}.`;

/** Server texts the UI recognises (G6); shown only when the server (or a certain 404 page state) says so. */
export const SKU_NOT_FOUND = 'SKU not found';
export const INSUFFICIENT_INVENTORY = 'Insufficient inventory';

const INTEGER = /^-?\d+$/;
const DIGITS = /^\d+$/;

export function skuIdReason(value: string, emptyText: string = SKU_ID_EMPTY): string | null {
  if (value === '') return emptyText;
  return SKU_ID_PATTERN.test(value) ? null : SKU_ID_INVALID;
}

export function quantityReason(value: string): string | null {
  if (!INTEGER.test(value) || Number(value) < 1) return QUANTITY_REASON;
  if (Number(value) > MAX_INT32) return `At most ${MAX_INT32.toLocaleString()}.`;
  return null;
}

/** The list's page size: the server ignores or caps values outside 1–250 (R4), so this only explains. */
export function limitReason(value: string): string | null {
  if (value === '') return null;
  const n = Number(value);
  return INTEGER.test(value) && n >= 1 && n <= MAX_PAGE ? null : LIMIT_REASON;
}

/* SKU details (DESIGN-V2 §8 field rules, the same CHECKs the server enforces). */
export const NAME_MAX = 120;
export const DESCRIPTION_MAX = 2000;
export const IMAGES_MAX = 10;
export const IMAGE_URL_MAX = 2048;
export const NAME_EMPTY = 'Enter a name.';
export const COST_PAIR = 'Enter both an amount and a currency, or leave both empty.';
export const COST_AMOUNT = 'Enter a whole number of minor units (e.g. cents), 0 or more.';
export const COST_CURRENCY = 'Enter a three-letter uppercase currency code, e.g. USD.';
export const INITIAL_STOCK = `Enter a whole number from 0 to ${MAX_INT32.toLocaleString()}.`;
const CURRENCY = /^[A-Z]{3}$/;

export function nameReason(value: string): string | null {
  if (value.trim() === '') return NAME_EMPTY;
  return value.length > NAME_MAX ? `At most ${NAME_MAX} characters.` : null;
}

export function descriptionReason(value: string): string | null {
  return value.length > DESCRIPTION_MAX ? `At most ${DESCRIPTION_MAX.toLocaleString()} characters.` : null;
}

/**
 * Amount in minor units. Above Number.MAX_SAFE_INTEGER the page could not send the digits
 * exactly (JSON numbers go through a double), so that is refused here although the server's
 * int64 would take it (FE39).
 */
export function costAmountReason(amount: string, currency: string): string | null {
  if (amount === '') return currency === '' ? null : COST_PAIR;
  if (!DIGITS.test(amount)) return COST_AMOUNT;
  if (Number(amount) > Number.MAX_SAFE_INTEGER) return `At most ${Number.MAX_SAFE_INTEGER.toLocaleString()}.`;
  return null;
}

export function costCurrencyReason(amount: string, currency: string): string | null {
  if (currency === '') return amount === '' ? null : COST_PAIR;
  return CURRENCY.test(currency) ? null : COST_CURRENCY;
}

/** One URL per line; surrounding spaces and blank lines are ignored. */
export function parseImages(text: string): string[] {
  return text
    .split(/\r?\n/)
    .map((l) => l.trim())
    .filter((l) => l !== '');
}

export function imagesReason(text: string): string | null {
  const urls = parseImages(text);
  if (urls.length > IMAGES_MAX) return `At most ${IMAGES_MAX} image URLs.`;
  for (const [i, u] of urls.entries()) {
    if (u.length > IMAGE_URL_MAX) return `Line ${i + 1}: at most ${IMAGE_URL_MAX.toLocaleString()} characters.`;
    let ok = false;
    try {
      const parsed = new URL(u);
      ok = parsed.protocol === 'http:' || parsed.protocol === 'https:';
    } catch {
      ok = false;
    }
    if (!ok) return `Line ${i + 1}: enter an absolute http or https URL.`;
  }
  return null;
}

/** Optional; empty means the server default of 0. */
export function initialStockReason(value: string): string | null {
  if (value === '') return null;
  return DIGITS.test(value) && Number(value) <= MAX_INT32 ? null : INITIAL_STOCK;
}
