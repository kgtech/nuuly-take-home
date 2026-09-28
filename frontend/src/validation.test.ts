import { describe, expect, it } from 'vitest';
import {
  CONTROL_CHARS,
  COST_AMOUNT,
  COST_CURRENCY,
  COST_PAIR,
  COST_TOO_LARGE,
  IMAGE_URL_RULE,
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
  it.each(['', 'abc', '0', '-1', '1.5', '1e-3', '1e10'])('rejects %j with the whole-number reason', (v) => {
    expect(quantityReason(v)).toBe(v === '1e10' ? `At most ${(2_147_483_647).toLocaleString()}.` : QUANTITY_REASON);
  });
  it('rejects a value over the int32 cap with a formatted number', () => {
    expect(quantityReason('2147483648')).toBe(`At most ${(2_147_483_647).toLocaleString()}.`);
  });
  // A number input reports a typed 1e3 as "1e3"; Number() reads it as 1000, which the server accepts (F-04).
  it.each(['1', '250', '2147483647', '1e3', '2.147483647e9'])('accepts %s', (v) => {
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
  it('name: 1–120 characters, not blank by Java isBlank (NBSP is not blank there), no control characters', () => {
    expect(nameReason('')).toBe('Enter a name.');
    expect(nameReason('   ')).toBe('Enter a name.');
    expect(nameReason('\t  　')).toBe('Enter a name.');
    expect(nameReason(' ')).toBeNull();
    expect(nameReason('a'.repeat(121))).toBe('At most 120 characters.');
    expect(nameReason('Linen dress')).toBeNull();
    expect(nameReason('a'.repeat(120))).toBeNull();
    expect(nameReason('Linen\ndress')).toBe(CONTROL_CHARS);
    expect(nameReason('Linen\tdress')).toBe(CONTROL_CHARS);
    expect(nameReason('Linen\u0007')).toBe(CONTROL_CHARS);
    expect(nameReason('Linen\u007F')).toBe(CONTROL_CHARS);
    expect(nameReason('Linen \uD83D')).toBe(CONTROL_CHARS); // unpaired surrogate
    expect(nameReason('Linen 👗')).toBeNull(); // a paired one is a dress emoji
  });
  it('description: up to 2000 characters; newline and tab are the only control characters allowed', () => {
    expect(descriptionReason('')).toBeNull();
    expect(descriptionReason('a'.repeat(2000))).toBeNull();
    expect(descriptionReason('a'.repeat(2001))).toBe(`At most ${(2000).toLocaleString()} characters.`);
    expect(descriptionReason('line\n\tindented')).toBeNull();
    expect(descriptionReason('bell\u0007')).toBe(CONTROL_CHARS);
    expect(descriptionReason('cr\r\n')).toBe(CONTROL_CHARS);
    expect(descriptionReason('del\u007F')).toBe(CONTROL_CHARS);
  });
  it('cost: both or neither; amount a whole number ≥ 0 (exponent forms read as numbers); currency three uppercase letters', () => {
    expect(costAmountReason('', '')).toBeNull();
    expect(costCurrencyReason('', '')).toBeNull();
    expect(costAmountReason('', 'USD')).toBe(COST_PAIR);
    expect(costCurrencyReason('100', '')).toBe(COST_PAIR);
    expect(costAmountReason('-1', 'USD')).toBe(COST_AMOUNT);
    expect(costAmountReason('1.5', 'USD')).toBe(COST_AMOUNT);
    expect(costAmountReason('abc', 'USD')).toBe(COST_AMOUNT);
    expect(costAmountReason('0', 'USD')).toBeNull();
    expect(costAmountReason('1e3', 'USD')).toBeNull();
    expect(costAmountReason('9007199254740991', 'USD')).toBeNull();
    expect(costAmountReason('9007199254740992', 'USD')).toBe(COST_TOO_LARGE);
    expect(costAmountReason('1e16', 'USD')).toBe(COST_TOO_LARGE);
    expect(COST_TOO_LARGE).toBe(`Amounts above ${Number.MAX_SAFE_INTEGER.toLocaleString()} can't be entered or edited here.`);
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
    expect(imagesReason('ftp://a.example/1.jpg')).toBe(`Line 1: ${IMAGE_URL_RULE}`);
    expect(imagesReason('https://a.example/ok\n/relative.jpg')).toBe(`Line 2: ${IMAGE_URL_RULE}`);
    expect(imagesReason('not a url')).toBe(`Line 1: ${IMAGE_URL_RULE}`);
    expect(imagesReason(`https://a.example/${'x'.repeat(2048)}`)).toBe(`Line 1: at most ${(2048).toLocaleString()} characters.`);
    expect(imagesReason(Array.from({ length: 11 }, (_, i) => `https://a.example/${i}`).join('\n'))).toBe('At most 10 image URLs.');
    expect(imagesReason(Array.from({ length: 10 }, (_, i) => `https://a.example/${i}`).join('\n'))).toBeNull();
  });
  // The server parses with java.net.URI and requires toASCIIString() to round-trip (F-01): ASCII only,
  // percent-encoded, lowercase scheme, a host of letters, digits, '.' and '-'.
  it.each([
    'https://a.example/café.jpg',
    'https://a.example/a b.jpg',
    'HTTP://a.example/1.jpg',
    'Https://a.example/1.jpg',
    'https://my_host.example/1.jpg',
    'https://a.example/1|2.jpg',
    'https://a.example/{id}.jpg',
    'https://a.example/1.jpg^',
    'https://a.example/"1".jpg',
    'https://a.example/<1>.jpg',
    'https://a.example/1.jpg`',
    'https://a.example/a\\b.jpg',
    'https://a.example/1.jpg\u007F',
    'https://',
    'https:///path',
    'https://a.example:80x/1.jpg',
  ])('rejects %j, which java.net.URI or the ASCII round-trip refuses', (u) => {
    expect(imagesReason(u)).toBe(`Line 1: ${IMAGE_URL_RULE}`);
  });
  it.each([
    'https://a.example/caf%C3%A9.jpg',
    'https://a.example/a%20b.jpg?x=1&y=2#frag',
    'http://user:pw@a.example:8080/p/1.jpg',
    'https://a-b.example.co.uk/1.jpg',
    "https://a.example/~u/1.jpg;v=2!$&'()*+,=@:",
    'https://127.0.0.1/1.jpg',
  ])('accepts %s', (u) => {
    expect(imagesReason(u)).toBeNull();
  });
  it('does not rewrite what the user typed', () => {
    expect(parseImages('https://a.example/café.jpg')).toEqual(['https://a.example/café.jpg']);
  });
  // java.net.URI accepts an IPv6 literal host (F-fe-01); an underscore host, a space or non-ASCII stay rejected.
  it.each(['http://[::1]/a.png', 'https://[2001:db8::1]:8443/x.jpg', 'https://[fe80::1]/'])('accepts the IPv6 host in %s', (u) => {
    expect(imagesReason(u)).toBeNull();
  });
  it.each(['https://my_host.example/1.jpg', 'https://a.example/a b.jpg', 'https://a.example/café.jpg', 'https://[::1/x', 'https://[zz::1]/x'])(
    'still rejects %j',
    (u) => {
      expect(imagesReason(u)).toBe(`Line 1: ${IMAGE_URL_RULE}`);
    },
  );
  // Only \r, space and tab are stripped from a line (F-fe-02): an NBSP or U+FEFF stays and fails the rule, as it
  // would on the server, instead of a request that differs from the visible input.
  it('strips only carriage returns, spaces and tabs from image lines', () => {
    expect(parseImages(' \thttps://a.example/x \r\n\r\n\t https://b.example/y\t')).toEqual(['https://a.example/x', 'https://b.example/y']);
    expect(parseImages(' https://a.example/x')).toEqual([' https://a.example/x']);
    expect(parseImages('﻿https://a.example/x')).toEqual(['﻿https://a.example/x']);
    expect(imagesReason('https://a.example/x ')).toBe(`Line 1: ${IMAGE_URL_RULE}`);
    expect(imagesReason(' ')).toBe(`Line 1: ${IMAGE_URL_RULE}`);
  });
  it('initial stock: optional, a whole number from 0 to 2,147,483,647 (exponent forms read as numbers)', () => {
    expect(initialStockReason('')).toBeNull();
    expect(initialStockReason('0')).toBeNull();
    expect(initialStockReason('1e3')).toBeNull();
    expect(initialStockReason('2147483647')).toBeNull();
    expect(initialStockReason('-1')).toBe(INITIAL_STOCK);
    expect(initialStockReason('1.5')).toBe(INITIAL_STOCK);
    expect(initialStockReason('2147483648')).toBe(INITIAL_STOCK);
    expect(INITIAL_STOCK).toBe(`Enter a whole number from 0 to ${(2_147_483_647).toLocaleString()}.`);
  });
});
