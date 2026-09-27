import { describe, expect, it } from 'vitest';
import { nextLink } from './link';

describe('nextLink', () => {
  it('returns the rel="next" URL', () => {
    expect(nextLink('<http://localhost:8080/inventory?limit=2&after=B>; rel="next"')).toBe(
      'http://localhost:8080/inventory?limit=2&after=B',
    );
  });
  it('picks next out of several links', () => {
    expect(
      nextLink('<http://x/a>; rel="prev", <http://x/b?after=Z%20Q>; rel="next"'),
    ).toBe('http://x/b?after=Z%20Q');
  });
  it('returns null without a next link', () => {
    expect(nextLink(null)).toBeNull();
    expect(nextLink('')).toBeNull();
    expect(nextLink('<http://x/a>; rel="prev"')).toBeNull();
  });
});
