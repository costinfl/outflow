import { useSearchParams } from 'react-router'

/**
 * The home accounts filter (DESIGN: "Accounts filter at the top, all accounts by default"). It lives in the URL
 * (?accounts=1,2) and travels with every drill-through link, so a number and the list behind it use the same accounts.
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
  return {
    ids,
    /** For API calls: `{ accounts: [1, 2] }`, or nothing for all accounts. */
    query: ids.length > 0 ? { accounts: ids } : {},
    /** A cache key part for useApi. */
    key: ids.join(','),
    /** Adds the filter to an in-app link. */
    withAccounts: (path: string) =>
      ids.length === 0 ? path : `${path}${path.includes('?') ? '&' : '?'}accounts=${ids.join(',')}`,
  }
}
