import { Link } from 'react-router'
import type { MonthSummary } from '../api/types'
import { formatMoney, formatMonth, formatMonthRange, perMonth } from '../lib/format'
import { transactionsLink } from '../lib/links'
import { useAccountsFilter } from '../lib/accounts'
import { Card } from './Card'
import { Delta } from './Delta'

/** Block 1: how much did I spend, and is that more or less than usual? */
export function SpentBlock({ s }: { s: MonthSummary }) {
  const { withFilters } = useAccountsFilter()
  const money = (minor: number) => formatMoney(minor, s.currency)
  // The last 3 months: the figures are totals (so they equal the list behind them), shown as a per-month average.
  const smoothed = s.months > 1
  const range = formatMonthRange(s.periodFrom, s.month)
  const reference = smoothed
    ? s.baselineMonths === 1 ? 'the month before' : `the ${s.baselineMonths} months before`
    : s.baselineMonths === 1 ? 'your previous month' : `your ${s.baselineMonths}-month average`
  return (
    <Card title={smoothed ? `Spent per month, ${range}` : `Spent in ${formatMonth(s.month)}`} id="spent">
      <Link
        to={withFilters(transactionsLink(s.month, 'spend'))}
        className="block text-[clamp(2rem,10vw,3rem)] leading-tight font-semibold tracking-tight break-words text-ink hover:underline"
        aria-label={
          smoothed
            ? `Spent ${money(perMonth(s.spentMinor, s.monthsWithData))} a month, ${money(s.spentMinor)} in total: show the transactions`
            : `Spent ${money(s.spentMinor)}: show the transactions`
        }
      >
        {money(smoothed ? perMonth(s.spentMinor, s.monthsWithData) : s.spentMinor)}
      </Link>
      {smoothed && (
        <p className="text-sm text-ink-2">
          {money(s.spentMinor)} over {s.monthsWithData === 1 ? '1 month' : `${s.monthsWithData} months`} with data
        </p>
      )}
      <p className="mt-2 text-sm">
        {s.averageSpentMinor != null && s.deltaPct != null ? (
          <Delta
            pct={s.deltaPct}
            against={`${reference} (${money(s.averageSpentMinor)}${smoothed ? ' a month' : ''})`}
          />
        ) : (
          <span className="text-muted">No earlier months to compare with yet.</span>
        )}
      </p>
      <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-hairline pt-3 text-sm">
        <div>
          <dt className="text-muted">Income{smoothed && `, ${s.monthsWithData} months`}</dt>
          <dd>
            <Link to={withFilters(transactionsLink(s.month, 'income'))} className="font-medium text-ink hover:underline">
              {money(s.incomeMinor)}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-muted">Net{smoothed && `, ${s.monthsWithData} months`}</dt>
          <dd className="font-medium text-ink">
            {s.netMinor > 0 ? '+' : ''}
            {money(s.netMinor)}
          </dd>
        </div>
      </dl>
    </Card>
  )
}
