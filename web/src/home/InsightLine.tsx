import { Link } from 'react-router'
import type { MonthSummary } from '../api/types'
import { formatMoney } from '../lib/format'
import { transactionsLink } from '../lib/links'
import { useAccountsFilter } from '../lib/accounts'

/**
 * The one plain-language line under "Where it went" (DESIGN: "Restaurants are up 40% vs. your usual — 9 visits this
 * month"). The API only sends it when a category moved more than 25% and more than 100 of the currency per month.
 */
export function InsightLine({ s }: { s: MonthSummary }) {
  const { withFilters } = useAccountsFilter()
  const i = s.insight
  if (!i) return null
  const up = i.deltaPct > 0
  const money = (minor: number) => formatMoney(minor, s.currency)
  const count = `${i.transactionCount} ${i.transactionCount === 1 ? 'transaction' : 'transactions'}`
  return (
    <p className="mt-4 border-t border-hairline pt-3 text-sm text-ink-2">
      <span aria-hidden className={up ? 'text-bad' : 'text-good'}>
        {up ? '▲' : '▼'}
      </span>{' '}
      <Link to={withFilters(transactionsLink(s.month, 'spend', { category: i.categoryId }))} className="text-ink hover:underline">
        <span className="font-medium">{i.name}</span> {up ? 'up' : 'down'} {Math.abs(i.deltaPct)}% vs. your usual
      </Link>{' '}
      ({money(i.perMonthMinor)}
      {s.months === 3 ? ' a month' : ''} vs. {money(i.usualMinor)}) — {count} {s.months === 3 ? 'in these 3 months' : 'this month'}.
    </p>
  )
}
