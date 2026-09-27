import { describe, expect, it } from 'vitest';
import { parseRoute } from './useHashRoute';

describe('parseRoute', () => {
  it('decodes an encoded skuId', () => {
    expect(parseRoute('#/sku/a%2Fb')).toEqual({ name: 'sku', skuId: 'a/b' });
  });
  it('keeps the raw segment when the escape is malformed instead of throwing', () => {
    expect(parseRoute('#/sku/50%off')).toEqual({ name: 'sku', skuId: '50%off' });
  });
  it('maps the other routes', () => {
    expect(parseRoute('')).toEqual({ name: 'list' });
    expect(parseRoute('#/add')).toEqual({ name: 'add' });
    expect(parseRoute('#/x')).toEqual({ name: 'notFound', path: '/x' });
  });
});
