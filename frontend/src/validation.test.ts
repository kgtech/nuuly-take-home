import { describe, expect, it } from 'vitest';
import {
  limitReason,
  quantityReason,
  QUANTITY_REASON,
  SKU_ID_EMPTY,
  SKU_ID_EMPTY_TO_OPEN,
  SKU_ID_INVALID,
  skuIdReason,
} from './validation';

describe('quantityReason', () => {
  it.each(['', 'abc', '0', '-1', '1.5', '1e3'])('rejects %j with the whole-number reason', (v) => {
    expect(quantityReason(v)).toBe(QUANTITY_REASON);
  });
  it('rejects a value over the int32 cap with a formatted number', () => {
    expect(quantityReason('2147483648')).toBe(`At most ${(2_147_483_647).toLocaleString()}.`);
  });
  it.each(['1', '250', '2147483647'])('accepts %s', (v) => {
    expect(quantityReason(v)).toBeNull();
  });
});

describe('skuIdReason', () => {
  it('names the empty case, with a text per form', () => {
    expect(skuIdReason('')).toBe(SKU_ID_EMPTY);
    expect(skuIdReason('', SKU_ID_EMPTY_TO_OPEN)).toBe(SKU_ID_EMPTY_TO_OPEN);
  });
  it.each(['bad id', '-x', '.', 'a'.repeat(65), 'A/B', 'A;lot=7'])('rejects %j with the format', (v) => {
    expect(skuIdReason(v)).toBe(SKU_ID_INVALID);
    expect(SKU_ID_INVALID).toMatch(/^That isn't a valid SKU ID\. Use 1 to 64 letters, digits/);
  });
  it.each(['A', 'DRS-0142-S', 'a.b_c-d', '9', 'a'.repeat(64)])('accepts %s', (v) => {
    expect(skuIdReason(v)).toBeNull();
  });
});

describe('limitReason', () => {
  it.each(['0', '251', '500', '-1', '2.5', 'x'])('flags %j as outside 1–250', (v) => {
    expect(limitReason(v)).toBe('Outside 1–250. The service will use 250.');
  });
  it.each(['', '1', '250', '25'])('accepts %j', (v) => {
    expect(limitReason(v)).toBeNull();
  });
});
