import { Link } from 'react-router'
import type { MonthSummary } from '../api/types'
import { formatMoney, formatMonth } from '../lib/format'
import { transactionsLink } from '../lib/links'
import { Card } from './Card'
import { Delta } from './Delta'

/** Block 1: how much did I spend, and is that more or less than usual? */
export function SpentBlock({ s }: { s: MonthSummary }) {
  const money = (minor: number) => formatMoney(minor, s.currency)
  return (
    <Card title={`Spent in ${formatMonth(s.month)}`} id="spent">
      <Link
        to={transactionsLink(s.month, 'spend')}
        className="block text-[clamp(2rem,10vw,3rem)] leading-tight font-semibold tracking-tight break-words text-ink hover:underline"
        aria-label={`Spent ${money(s.spentMinor)}: show the transactions`}
      >
        {money(s.spentMinor)}
      </Link>
      <p className="mt-2 text-sm">
        {s.averageSpentMinor != null && s.deltaPct != null ? (
          <Delta
            pct={s.deltaPct}
            against={`your ${s.baselineMonths === 1 ? 'previous month' : `${s.baselineMonths}-month average`} (${money(s.averageSpentMinor)})`}
          />
        ) : (
          <span className="text-muted">No earlier months to compare with yet.</span>
        )}
      </p>
      <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-hairline pt-3 text-sm">
        <div>
          <dt className="text-muted">Income</dt>
          <dd>
            <Link to={transactionsLink(s.month, 'income')} className="font-medium text-ink hover:underline">
              {money(s.incomeMinor)}
            </Link>
          </dd>
        </div>
        <div>
          <dt className="text-muted">Net</dt>
          <dd className="font-medium text-ink">
            {s.netMinor > 0 ? '+' : ''}
            {money(s.netMinor)}
          </dd>
        </div>
      </dl>
    </Card>
  )
}
