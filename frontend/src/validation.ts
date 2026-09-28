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

/**
 * A number input reports a typed 1e3 as "1e3"; Number() reads it as 1000 and the body sends
 * that, which the server accepts, so the check is numeric, not a digits regex (F-04).
 * Returns null for anything that is not a finite integer (including "").
 */
function integerOf(value: string): number | null {
  if (value.trim() === '') return null;
  const n = Number(value);
  return Number.isInteger(n) ? n : null;
}

export function skuIdReason(value: string, emptyText: string = SKU_ID_EMPTY): string | null {
  if (value === '') return emptyText;
  return SKU_ID_PATTERN.test(value) ? null : SKU_ID_INVALID;
}

export function quantityReason(value: string): string | null {
  const n = integerOf(value);
  if (n === null || n < 1) return QUANTITY_REASON;
  if (n > MAX_INT32) return `At most ${MAX_INT32.toLocaleString()}.`;
  return null;
}

/** The list's page size: the server ignores or caps values outside 1–250 (R4), so this only explains. */
export function limitReason(value: string): string | null {
  if (value === '') return null;
  const n = integerOf(value);
  return n !== null && n >= 1 && n <= MAX_PAGE ? null : LIMIT_REASON;
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
export const CONTROL_CHARS = 'Remove control characters.';
/** A number input's validity.badInput: it shows text it cannot parse but reports "" (F-fe-03). */
export const NOT_A_NUMBER = 'Enter a number.';
export const COST_TOO_LARGE = `Amounts above ${Number.MAX_SAFE_INTEGER.toLocaleString()} can't be entered or edited here.`;
export const IMAGE_URL_RULE =
  'use the URL as a browser shows it: ASCII, percent-encoded, starting with http:// or https://';
const CURRENCY = /^[A-Z]{3}$/;

/**
 * Java's String.isBlank: Character.isWhitespace, which is the Unicode space separators except
 * the non-breaking ones (U+00A0, U+2007, U+202F) plus \t \n \v \f \r and U+001C–U+001F. JS
 * trim() would also strip NBSP and U+FEFF, which the server keeps, so it is not used (F-03).
 */
// eslint-disable-next-line no-control-regex -- matches the control characters the server rejects (F-03)
const JAVA_BLANK = /^[\t\n\v\f\r\x1C-\x1F \u1680\u2000-\u2006\u2008-\u200A\u2028\u2029\u205F\u3000]*$/;
/** The server rejects any char below 0x20, 0x7F and an unpaired surrogate; a description may hold \n and \t. */
// eslint-disable-next-line no-control-regex -- matches the control characters the server rejects (F-03)
const NAME_CONTROL = /[\x00-\x1F\x7F\p{Cs}]/u;
// eslint-disable-next-line no-control-regex -- matches the control characters the server rejects (F-03)
const DESCRIPTION_CONTROL = /[\x00-\x08\x0B-\x1F\x7F\p{Cs}]/u;

export function nameReason(value: string): string | null {
  if (JAVA_BLANK.test(value)) return NAME_EMPTY;
  if (value.length > NAME_MAX) return `At most ${NAME_MAX} characters.`;
  return NAME_CONTROL.test(value) ? CONTROL_CHARS : null;
}

export function descriptionReason(value: string): string | null {
  if (value.length > DESCRIPTION_MAX) return `At most ${DESCRIPTION_MAX.toLocaleString()} characters.`;
  return DESCRIPTION_CONTROL.test(value) ? CONTROL_CHARS : null;
}

/**
 * Amount in minor units. Above Number.MAX_SAFE_INTEGER the page could not send the digits
 * exactly (JSON numbers go through a double), so that is refused here although the server's
 * int64 would take it (FE39); a stored amount that large gets the same hint on the edit page.
 */
export function costAmountReason(amount: string, currency: string): string | null {
  if (amount === '') return currency === '' ? null : COST_PAIR;
  const n = integerOf(amount);
  if (n === null || n < 0) return COST_AMOUNT;
  if (n > Number.MAX_SAFE_INTEGER) return COST_TOO_LARGE;
  return null;
}

export function costCurrencyReason(amount: string, currency: string): string | null {
  if (currency === '') return amount === '' ? null : COST_PAIR;
  return CURRENCY.test(currency) ? null : COST_CURRENCY;
}

/** One URL per line; surrounding spaces and blank lines are ignored. */
export function parseImages(text: string): string[] {
  // Only \r, space and tab are stripped (F-fe-02): trim() would also drop NBSP and U+FEFF, and the request
  // must be what the user sees; such a line then fails the URL rule with its reason, as it would on the server.
  return text
    .split('\n')
    .map((l) => l.replace(/^[ \t\r]+|[ \t\r]+$/g, ''))
    .filter((l) => l !== '');
}

/**
 * What the server's java.net.URI check accepts (SkuDetails.requireAbsoluteHttpUrl, F-01): printable
 * ASCII 0x21–0x7E without the characters URI rejects (space, controls, `"<>\^`{|}`), a lowercase
 * http/https scheme, and a host of letters, digits, '.' and '-' (an underscore host makes
 * getHost() null), so that toASCIIString() round-trips. The value is checked as typed, never rewritten.
 */
// The host is a name of letters, digits, '.' and '-', or an IPv6 literal in brackets with an optional zone id
// (`[fe80::1%eth0]`), which java.net.URI also accepts (F-fe-01, R-03); both may carry a port.
const IMAGE_URL =
  /^https?:\/\/(?:[!$&'()*+,;=A-Za-z0-9._~%:-]*@)?(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+(?:%[A-Za-z0-9]+)?\])(?::\d*)?(?:[/?#][!-~]*)?$/;
const URI_REJECTS = /[^\x21-\x7E]|["<>\\^`{|}]/;

export function isServerImageUrl(u: string): boolean {
  return !URI_REJECTS.test(u) && IMAGE_URL.test(u);
}

export function imagesReason(text: string): string | null {
  const urls = parseImages(text);
  if (urls.length > IMAGES_MAX) return `At most ${IMAGES_MAX} image URLs.`;
  for (const [i, u] of urls.entries()) {
    if (u.length > IMAGE_URL_MAX) return `Line ${i + 1}: at most ${IMAGE_URL_MAX.toLocaleString()} characters.`;
    if (!isServerImageUrl(u)) return `Line ${i + 1}: ${IMAGE_URL_RULE}`;
  }
  return null;
}

/** Optional; empty means the server default of 0. */
export function initialStockReason(value: string): string | null {
  if (value === '') return null;
  const n = integerOf(value);
  return n !== null && n >= 0 && n <= MAX_INT32 ? null : INITIAL_STOCK;
}
