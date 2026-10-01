import { useSearchParams } from 'react-router'

/**
 * The home filters: accounts (DESIGN: "Accounts filter at the top, all accounts by default"; ?accounts=1,2), the
 * "last 3 months" view (?months=3) and the currency (?currency=EUR; absent = the accounts' main currency, CP6.17).
 * They live in the URL and travel with every drill-through link, so a number and the list behind it cover the same
 * accounts, months and currency.
 */
export function parseAccounts(value: string | null): number[] {
  if (!value) return []
  const ids = value
    .split(',')
    .map((s) => Number(s))
    .filter((n) => Number.isInteger(n) && n > 0)
  return [...new Set(ids)].sort((a, b) => a - b)
}

/** An ISO currency code from the URL, or undefined. */
export function parseCurrency(value: string | null): string | undefined {
  return value && /^[A-Z]{3}$/.test(value) ? value : undefined
}

export function useAccountsFilter() {
  const [params] = useSearchParams()
  const ids = parseAccounts(params.get('accounts'))
  const currency = parseCurrency(params.get('currency'))
  const months = params.get('months') === '3' ? 3 : 1
  return {
    ids,
    months,
    currency,
    /** For endpoints with a period: `{ months: 3 }`, or nothing for one month. */
    monthsQuery: months === 3 ? { months } : {},
    /** For API calls: `{ accounts: [1, 2], currency: 'EUR' }`, or nothing for all accounts in the main currency. */
    query: { ...(ids.length > 0 ? { accounts: ids } : {}), ...(currency ? { currency } : {}) },
    /** A cache key part for useApi. */
    key: `${ids.join(',')}:${currency ?? ''}`,
    /**
     * Adds the filters to an in-app link. `period: false` leaves out the 3-month view, for a link to one month's
     * numbers (the category screen's own figures are one month's).
     */
    withFilters: (path: string, { period = true }: { period?: boolean } = {}) => {
      const carry = [
        ...(ids.length > 0 ? [`accounts=${ids.join(',')}`] : []),
        ...(currency ? [`currency=${currency}`] : []),
        ...(period && months === 3 ? ['months=3'] : []),
      ]
      return carry.length === 0 ? path : `${path}${path.includes('?') ? '&' : '?'}${carry.join('&')}`
    },
  }
}
