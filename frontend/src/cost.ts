import type { SkuCost } from './api/client';

/**
 * Formats a cost given in minor units with its currency (FE36). Intl.NumberFormat
 * knows each currency's minor-unit count (2 for USD, 0 for JPY, 3 for KWD); when it
 * rejects the code the raw "<amount> <currency>" is shown instead.
 */
export function formatCost(cost: SkuCost, locale?: string): string {
  try {
    const nf = new Intl.NumberFormat(locale, { style: 'currency', currency: cost.currency });
    const digits = nf.resolvedOptions().maximumFractionDigits ?? 2;
    return nf.format(cost.amount / 10 ** digits);
  } catch {
    return `${cost.amount} ${cost.currency}`;
  }
}
