import { describe, expect, it } from 'vitest';
import {
  COST_AMOUNT,
  COST_CURRENCY,
  COST_PAIR,
  costAmountReason,
  costCurrencyReason,
  descriptionReason,
  imagesReason,
  INITIAL_STOCK,
  initialStockReason,
  limitReason,
  nameReason,
  parseImages,
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

describe('details reasons (DESIGN-V2 §8 field rules)', () => {
  it('name: 1–120 characters, not blank', () => {
    expect(nameReason('')).toBe('Enter a name.');
    expect(nameReason('   ')).toBe('Enter a name.');
    expect(nameReason('a'.repeat(121))).toBe('At most 120 characters.');
    expect(nameReason('Linen dress')).toBeNull();
    expect(nameReason('a'.repeat(120))).toBeNull();
  });
  it('description: up to 2000 characters', () => {
    expect(descriptionReason('')).toBeNull();
    expect(descriptionReason('a'.repeat(2000))).toBeNull();
    expect(descriptionReason('a'.repeat(2001))).toBe(`At most ${(2000).toLocaleString()} characters.`);
  });
  it('cost: both or neither; amount a whole number ≥ 0; currency three uppercase letters', () => {
    expect(costAmountReason('', '')).toBeNull();
    expect(costCurrencyReason('', '')).toBeNull();
    expect(costAmountReason('', 'USD')).toBe(COST_PAIR);
    expect(costCurrencyReason('100', '')).toBe(COST_PAIR);
    expect(costAmountReason('-1', 'USD')).toBe(COST_AMOUNT);
    expect(costAmountReason('1.5', 'USD')).toBe(COST_AMOUNT);
    expect(costAmountReason('abc', 'USD')).toBe(COST_AMOUNT);
    expect(costAmountReason('0', 'USD')).toBeNull();
    expect(costAmountReason('9007199254740991', 'USD')).toBeNull();
    expect(costAmountReason('9007199254740992', 'USD')).toBe(`At most ${Number.MAX_SAFE_INTEGER.toLocaleString()}.`);
    expect(costCurrencyReason('100', 'usd')).toBe(COST_CURRENCY);
    expect(costCurrencyReason('100', 'US')).toBe(COST_CURRENCY);
    expect(costCurrencyReason('100', 'USD')).toBeNull();
  });
  it('images: up to 10 absolute http(s) URLs of up to 2048 characters, one per line, blank lines ignored', () => {
    expect(imagesReason('')).toBeNull();
    expect(parseImages(' https://a.example/1.jpg \n\nhttp://b.example/2.png\n')).toEqual([
      'https://a.example/1.jpg',
      'http://b.example/2.png',
    ]);
    expect(imagesReason('https://a.example/1.jpg\nhttp://b.example/2.png')).toBeNull();
    expect(imagesReason('ftp://a.example/1.jpg')).toBe('Line 1: enter an absolute http or https URL.');
    expect(imagesReason('https://a.example/ok\n/relative.jpg')).toBe('Line 2: enter an absolute http or https URL.');
    expect(imagesReason('not a url')).toBe('Line 1: enter an absolute http or https URL.');
    expect(imagesReason(`https://a.example/${'x'.repeat(2048)}`)).toBe(`Line 1: at most ${(2048).toLocaleString()} characters.`);
    expect(imagesReason(Array.from({ length: 11 }, (_, i) => `https://a.example/${i}`).join('\n'))).toBe('At most 10 image URLs.');
    expect(imagesReason(Array.from({ length: 10 }, (_, i) => `https://a.example/${i}`).join('\n'))).toBeNull();
  });
  it('initial stock: optional, a whole number from 0 to 2,147,483,647', () => {
    expect(initialStockReason('')).toBeNull();
    expect(initialStockReason('0')).toBeNull();
    expect(initialStockReason('2147483647')).toBeNull();
    expect(initialStockReason('-1')).toBe(INITIAL_STOCK);
    expect(initialStockReason('1.5')).toBe(INITIAL_STOCK);
    expect(initialStockReason('2147483648')).toBe(INITIAL_STOCK);
    expect(INITIAL_STOCK).toBe(`Enter a whole number from 0 to ${(2_147_483_647).toLocaleString()}.`);
  });
});
