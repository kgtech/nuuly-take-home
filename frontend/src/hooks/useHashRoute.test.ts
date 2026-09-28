import { describe, expect, it } from 'vitest';
import { editHref, NEW_HREF, parseRoute, skuHref } from './useHashRoute';

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
    expect(parseRoute('#/new')).toEqual({ name: 'new' });
    expect(parseRoute('#/x')).toEqual({ name: 'notFound', path: '/x' });
  });
  it('maps the edit route and its hrefs', () => {
    expect(parseRoute('#/sku/a%20b/edit')).toEqual({ name: 'edit', skuId: 'a b' });
    expect(parseRoute(editHref('DRS-1'))).toEqual({ name: 'edit', skuId: 'DRS-1' });
    expect(parseRoute(skuHref('DRS-1'))).toEqual({ name: 'sku', skuId: 'DRS-1' });
    expect(parseRoute(NEW_HREF)).toEqual({ name: 'new' });
    expect(editHref('a/b')).toBe('#/sku/a%2Fb/edit');
  });
});
