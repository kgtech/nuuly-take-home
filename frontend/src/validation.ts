/**
 * Mirrors the server's rules (G11, InventoryQuantity, R4) as field-specific reasons.
 * A reason makes the submit button unavailable and is shown under the field; it is
 * never the server's text (FE10 as amended by FE30). Only input the server is certain
 * to reject gets a reason, so nothing the server might accept is ever blocked.
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
