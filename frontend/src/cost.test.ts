import { describe, expect, it } from 'vitest';
import { formatCost } from './cost';

describe('formatCost', () => {
  it('formats minor units with the currency through Intl.NumberFormat', () => {
    expect(formatCost({ amount: 12900, currency: 'USD' }, 'en-US')).toBe('$129.00');
    expect(formatCost({ amount: 5, currency: 'EUR' }, 'en-US')).toBe('€0.05');
    expect(formatCost({ amount: 0, currency: 'GBP' }, 'en-US')).toBe('£0.00');
  });
  it('uses the currency\'s own minor-unit count', () => {
    expect(formatCost({ amount: 500, currency: 'JPY' }, 'en-US')).toBe('¥500');
    expect(formatCost({ amount: 12345, currency: 'KWD' }, 'en-US')).toBe('KWD 12.345');
  });
  it('falls back to "<amount> <currency>" when Intl rejects the code', () => {
    expect(formatCost({ amount: 500, currency: 'ab' }, 'en-US')).toBe('500 ab');
    expect(formatCost({ amount: 500, currency: '' }, 'en-US')).toBe('500 ');
  });
});
