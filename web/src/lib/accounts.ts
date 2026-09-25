import { useSearchParams } from 'react-router'

/**
 * The home filters: accounts (DESIGN: "Accounts filter at the top, all accounts by default"; ?accounts=1,2) and the
 * "last 3 months" view (?months=3). They live in the URL and travel with every drill-through link, so a number and
 * the list behind it cover the same accounts and months.
 */
export function parseAccounts(value: string | null): number[] {
  if (!value) return []
  const ids = value
    .split(',')
    .map((s) => Number(s))
    .filter((n) => Number.isInteger(n) && n > 0)
  return [...new Set(ids)].sort((a, b) => a - b)
}

export function useAccountsFilter() {
  const [params] = useSearchParams()
  const ids = parseAccounts(params.get('accounts'))
  const months = params.get('months') === '3' ? 3 : 1
  return {
    ids,
    months,
    /** For endpoints with a period: `{ months: 3 }`, or nothing for one month. */
    monthsQuery: months === 3 ? { months } : {},
    /** For API calls: `{ accounts: [1, 2] }`, or nothing for all accounts. */
    query: ids.length > 0 ? { accounts: ids } : {},
    /** A cache key part for useApi. */
    key: ids.join(','),
    /**
     * Adds the filters to an in-app link. `period: false` leaves out the 3-month view, for a link to one month's
     * numbers (the category screen's own figures are one month's).
     */
    withFilters: (path: string, { period = true }: { period?: boolean } = {}) => {
      const carry = [...(ids.length > 0 ? [`accounts=${ids.join(',')}`] : []), ...(period && months === 3 ? ['months=3'] : [])]
      return carry.length === 0 ? path : `${path}${path.includes('?') ? '&' : '?'}${carry.join('&')}`
    },
  }
}
